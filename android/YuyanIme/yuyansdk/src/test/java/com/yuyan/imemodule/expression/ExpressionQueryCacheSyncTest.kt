package com.yuyan.imemodule.expression

import com.yuyan.imemodule.expression.model.ExpressionAsset
import com.yuyan.imemodule.expression.model.ExpressionCatalogDocument
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ExpressionQueryCacheSyncTest {
    private lateinit var server: MockWebServer
    private lateinit var root: File
    private val scopes = mutableListOf<CoroutineScope>()
    private val bytes = "GIF89a-original-frames".toByteArray()
    private val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private val asset get() = ExpressionAsset("future", "prebuilt", "gif", "v2", "prebuilt/future.gif",
        sha256 = digest, width = 240, height = 240, embeddedText = "你们", distribution = "remote",
        url = server.url("/uploads/expression/prebuilt/future.gif").toString())

    @Before fun before() { server = MockWebServer().apply { start() }; root = java.nio.file.Files.createTempDirectory("query-cache").toFile() }
    @After fun after() { scopes.forEach { it.cancel() }; server.shutdown(); root.deleteRecursively() }
    private fun sync(initial: List<ExpressionAsset> = emptyList()): ExpressionSync {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        return ExpressionSync(OkHttpClient(), server.url("/").toString().trimEnd('/'), "device", ExpressionCatalog(
            ExpressionCatalogDocument("v1", initial, emptyList(), emptyList())), ExpressionCache(root), scope)
    }
    private fun response(source: String? = "ai-original", value: ExpressionAsset = asset): MockResponse {
        val encoded = Json.encodeToString(value).dropLast(1) + (source?.let { ",\"sourceType\":\"$it\"" } ?: "") + "}"
        return MockResponse().setBody("{\"results\":[$encoded]}")
    }
    private fun waitFor(message: String, timeoutMs: Long = 4000, condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!condition() && System.nanoTime() < end) Thread.sleep(10)
        assertTrue(message, condition())
    }

    @Test fun `取消输入只取消订阅迟到原GIF仍落盘且重建Sync断网立即命中`() {
        val requestStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = if (request.path!!.startsWith("/api/")) {
                requestStarted.countDown(); release.await(3, TimeUnit.SECONDS); response()
            } else MockResponse().setBody(okio.Buffer().write(bytes))
        }
        val seen = AtomicInteger()
        val first = sync().search("你们", 1, { true }) { if (it.isNotEmpty()) seen.incrementAndGet() }
        assertTrue(requestStarted.await(3, TimeUnit.SECONDS))
        first.cancel()
        release.countDown()
        val cache = ExpressionCache(root)
        waitFor("迟到原件必须落盘") { cache.validFile(asset.version, asset.fileName, digest) != null }
        assertEquals("取消的订阅不能收到结果", 0, seen.get())
        val requestCount = server.requestCount
        val local = CountDownLatch(1)
        val next = sync().search("你们", 2, { true }) { results ->
            results.singleOrNull()?.let { assertTrue(it.resolvedPreviewUrl!!.startsWith("file://")); local.countDown() }
        }
        assertTrue(local.await(1, TimeUnit.SECONDS))
        runBlocking { next.join() }
        assertEquals("新鲜完整缓存不应联网", requestCount, server.requestCount)
    }

    @Test fun `同查询重入复用在途请求但旧订阅不发布`() {
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = if (request.path!!.startsWith("/api/")) {
                started.countDown(); release.await(3, TimeUnit.SECONDS); response()
            } else MockResponse().setBody(okio.Buffer().write(bytes))
        }
        val sync = sync(); val count = AtomicInteger()
        val first = sync.search("你们", 1, { true }) { }
        assertTrue(started.await(3, TimeUnit.SECONDS)); first.cancel()
        val second = sync.search("你们", 2, { true }) { if (it.isNotEmpty()) count.incrementAndGet() }
        release.countDown(); runBlocking { second.join() }
        waitFor("原件缓存完成") { ExpressionCache(root).validFile(asset.version, asset.fileName, digest) != null }
        assertEquals("一个推荐请求和一个原件请求", 2, server.requestCount)
        assertTrue(count.get() > 0)
    }

    @Test fun `未知无来源旧代理结果不展示不预取不持久化`() {
        server.enqueue(response(null))
        val shown = mutableListOf<ExpressionAsset>()
        runBlocking { sync().search("你们", 1, { true }) { shown += it }.join() }
        assertTrue(shown.isEmpty())
        assertEquals(1, server.requestCount)
        assertNull(ExpressionCache(root).validFile(asset.version, asset.fileName, digest))
    }

    @Test fun `元数据先发布不等待原件慢下载`() {
        val originalStarted = CountDownLatch(1); val release = CountDownLatch(1); val shown = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = if (request.path!!.startsWith("/api/")) response()
            else { originalStarted.countDown(); release.await(3, TimeUnit.SECONDS); MockResponse().setBody(okio.Buffer().write(bytes)) }
        }
        sync().search("你们", 1, { true }) { if (it.isNotEmpty()) shown.countDown() }
        assertTrue(shown.await(1, TimeUnit.SECONDS))
        assertTrue("无UI解码也必须后台预取", originalStarted.await(1, TimeUnit.SECONDS))
        release.countDown()
        waitFor("原件落盘") { ExpressionCache(root).validFile(asset.version, asset.fileName, digest) != null }
    }
    @Test fun `原件首次失败后重输沿用查询索引只重试图片`() {
        val api = AtomicInteger(); val originals = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = if (request.path!!.startsWith("/api/")) {
                api.incrementAndGet(); response()
            } else if (originals.incrementAndGet() == 1) MockResponse().setResponseCode(503)
            else MockResponse().setBody(okio.Buffer().write(bytes))
        }
        val first = sync()
        runBlocking { first.search("你们", 1, { true }) { }.join() }
        waitFor("首次原件请求") { originals.get() == 1 }
        // 新实例验证恢复来自磁盘索引而非内存中的查询 Deferred。
        runBlocking { sync().search("你们", 2, { true }) { }.join() }
        waitFor("失败的原件允许重试") { ExpressionCache(root).validFile(asset.version, asset.fileName, digest) != null }
        assertEquals(1, api.get())
        assertEquals(2, originals.get())
    }

    @Test fun `跨源图片和未知来源类型不能进入预取`() {
        val external = asset.copy(url = "https://untrusted.example/original.gif")
        val payload = Json.encodeToString(external).dropLast(1) + ",\"sourceType\":\"ai-original\"}"
        server.enqueue(MockResponse().setBody("{\"results\":[$payload]}"))
        val shown = mutableListOf<ExpressionAsset>()
        runBlocking { sync().search("你们", 1, { true }) { shown += it }.join() }
        assertTrue(shown.isEmpty())
        server.enqueue(response("unknown", asset.copy(embeddedText = "其他")))
        runBlocking { sync().search("其他", 2, { true }) { shown += it }.join() }
        assertTrue(shown.isEmpty())
        assertEquals(2, server.requestCount)
    }

    @Test fun `取消owner在原件响应体迟到时也终止HTTP`() {
        val bytesStarted = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = if (request.path!!.startsWith("/api/")) response()
            else { bytesStarted.countDown(); MockResponse().setBody(okio.Buffer().write(bytes)).setBodyDelay(3, TimeUnit.SECONDS) }
        }
        val local = sync()
        runBlocking { local.search("你们", 1, { true }) { }.join() }
        assertTrue(bytesStarted.await(2, TimeUnit.SECONDS))
        waitFor("响应头已到且正在读body") { File(root, "expression-query/originals").listFiles().orEmpty().any { it.extension == "part" } }
        scopes.last().cancel()
        // 不等超时；响应头已到但 body 尚未到时，取消也应立即释放查询后台任务。
        val owner = scopes.last().coroutineContext[kotlinx.coroutines.Job]!!
        waitFor("owner取消应快速完成，而非等待原件body超时", timeoutMs = 1000) { owner.isCompleted }
        assertNull(ExpressionCache(root).validFile(asset.version, asset.fileName, digest))
    }

    @Test fun `APK索引完全匹配的已审计素材兼容无来源字段`() {
        server.enqueue(response(null)); server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
        val visible = AtomicInteger()
        runBlocking { sync(listOf(asset)).search("你们", 1, { true }) { if (it.isNotEmpty()) visible.incrementAndGet() }.join() }
        waitFor("已审计原件下载") { ExpressionCache(root).validFile(asset.version, asset.fileName, digest) != null }
        assertTrue(visible.get() > 0)
    }

    @Test fun `繁忙时最多四个后台查询且不会无限排队`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE)
        }
        val sync = sync(); val local = CountDownLatch(8)
        repeat(8) { sync.search("你们$it", it.toLong(), { true }) { local.countDown() } }
        assertTrue(local.await(2, TimeUnit.SECONDS))
        waitFor("四个后台请求已启动") { server.requestCount >= 4 }
        assertEquals(4, server.requestCount)
        scopes.last().cancel()
    }

    @Test fun `查询缓存中的新版同id原件优先于APK旧索引`() {
        val old = asset.copy(version = "v1", sha256 = "a".repeat(64), distribution = "bundled")
        server.enqueue(response()); server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
        runBlocking { sync(listOf(old)).search("你们", 1, { true }) { }.join() }
        waitFor("新版原件缓存") { ExpressionCache(root).validFile(asset.version, asset.fileName, digest) != null }
        val seen = mutableListOf<ExpressionAsset>()
        runBlocking { sync(listOf(old)).search("你们", 2, { true }) { seen += it }.join() }
        assertEquals("v2", seen.single().version)
        assertTrue(seen.single().resolvedPreviewUrl!!.startsWith("file://"))
        assertEquals(2, server.requestCount)
    }

    @Test fun `七天内多次读取不延长TTL过期刷新失败仍保留原GIF及旧时间`() {
        val storage = ExpressionCache(root)
        val endpoint = server.url("/").toString().trimEnd('/')
        val now = System.currentTimeMillis()
        val sixDaysAgo = now - TimeUnit.DAYS.toMillis(6)
        val sixDayCache = ExpressionQueryCache(storage, now = { sixDaysAgo })
        sixDayCache.write(endpoint, "你们", listOf(ExpressionQueryCache.Item(asset, "ai-original")))
        requireNotNull(sixDayCache.writeOriginal(digest, bytes.inputStream()))
        repeat(2) {
            val seen = mutableListOf<ExpressionAsset>()
            runBlocking { sync().search("你们", it.toLong(), { true }) { seen += it }.join() }
            assertEquals(asset.id, seen.single().id)
        }
        assertEquals(0, server.requestCount)
        assertEquals(sixDaysAgo, ExpressionQueryCache(storage).read(endpoint, "你们")!!.fetchedAt)
        val eightDaysAgo = now - TimeUnit.DAYS.toMillis(8)
        ExpressionQueryCache(storage, now = { eightDaysAgo }).write(endpoint, "你们",
            listOf(ExpressionQueryCache.Item(asset, "ai-original")))
        server.enqueue(MockResponse().setResponseCode(503))
        val stale = mutableListOf<ExpressionAsset>()
        runBlocking { sync().search("你们", 3, { true }) { stale += it }.join() }
        assertEquals(asset.id, stale.single().id)
        assertTrue(stale.single().resolvedPreviewUrl!!.startsWith("file://"))
        assertEquals(1, server.requestCount)
        assertTrue(server.takeRequest().path!!.startsWith("/api/v1/mobile/expressions/recommend"))
        assertEquals(eightDaysAgo, ExpressionQueryCache(storage).read(endpoint, "你们")!!.fetchedAt)
        assertNotNull(storage.validFile(asset.version, asset.fileName, digest))
    }

}
