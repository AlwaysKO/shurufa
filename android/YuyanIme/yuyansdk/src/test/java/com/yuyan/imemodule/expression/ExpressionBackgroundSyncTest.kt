package com.yuyan.imemodule.expression

import com.yuyan.imemodule.expression.model.ExpressionAsset
import com.yuyan.imemodule.expression.model.ExpressionCatalogDocument
import com.yuyan.imemodule.expression.model.ExpressionRecommendationGroup
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ExpressionBackgroundSyncTest {
    private lateinit var server: MockWebServer
    private lateinit var root: File
    private lateinit var scope: CoroutineScope
    private val bytes = "recommended-original".toByteArray()
    private fun asset(id: String = "new", data: ByteArray = bytes): ExpressionAsset {
        val sha = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        return ExpressionAsset(id, "prebuilt", "png", sha, "stickers/$id.png", sha256 = sha,
            width = 198, height = 198, keywords = listOf("嘚瑟"), url = "/uploads/stickers/$id.png",
            distribution = "remote", sourceType = "owner-upload")
    }
    private fun document(version: String, assets: List<ExpressionAsset> = emptyList(),
                         ids: List<String> = assets.map { it.id }) = ExpressionCatalogDocument(
        version, assets, emptyList(), emptyList(), complete = true,
        recommendationGroups = listOf(ExpressionRecommendationGroup("嘚瑟", listOf("嘚瑟"), ids)))
    private fun sync() = ExpressionSync(OkHttpClient(), server.url("").toString().trimEnd('/'),
        "device", ExpressionCatalog(document("apk")), ExpressionCache(root), scope)
    private fun version(value: String) = server.enqueue(MockResponse().setBody("{\"version\":\"$value\"}"))
    private fun catalog(value: ExpressionCatalogDocument) = server.enqueue(MockResponse().setBody(Json.encodeToString(value)))
    @Before fun setup() {
        root = java.nio.file.Files.createTempDirectory("expression-background").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        server = MockWebServer().apply { start(); (dispatcher as okhttp3.mockwebserver.QueueDispatcher).setFailFast(true) }
    }
    @After fun cleanup() { scope.cancel(); server.shutdown(); root.deleteRecursively() }

    @Test fun `版本探测只调用一个接口不拉目录或图片`() = runBlocking {
        val sync = sync()
        version("v2")
        assertEquals("v2", sync.remoteVersion())
        assertTrue(sync.backgroundSyncNeeded("v2"))
        assertEquals(1, server.requestCount)
        assertEquals("/api/v1/mobile/expressions/versions", server.takeRequest().path)
        assertEquals("apk", sync.currentCatalog().document.version)
    }

    @Test fun `WiFi任务使用已检测版本不重复调用版本接口`() = runBlocking {
        val sync = sync()
        catalog(document("v2", listOf(asset())))
        server.enqueue(MockResponse().setBody(String(bytes)))
        assertTrue(sync.syncInBackground(expectedVersion = "v2"))
        assertFalse(sync.backgroundSyncNeeded("v2"))
        assertEquals(2, server.requestCount)
        assertTrue(server.takeRequest().path!!.contains("/catalog"))
        assertTrue(server.takeRequest().path!!.contains("/uploads/"))
    }

    @Test fun `等待WiFi期间图库再次更新按最新完整目录同步`() = runBlocking {
        val sync = sync()
        catalog(document("v3", listOf(asset())))
        server.enqueue(MockResponse().setBody(String(bytes)))
        assertTrue(sync.syncInBackground(expectedVersion = "v2"))
        assertEquals("v3", sync.currentCatalog().document.version)
        assertFalse(sync.backgroundSyncNeeded("v3"))
        assertEquals(2, server.requestCount)
    }

    @Test fun `WiFi目录下载失败向任务报告可重试且保留旧目录`() = runBlocking {
        val sync = sync()
        server.enqueue(MockResponse().setResponseCode(503))
        val result = runCatching { sync.syncInBackground(expectedVersion = "v2") }
        assertTrue(result.exceptionOrNull() is java.io.IOException)
        assertEquals("apk", sync.currentCatalog().document.version)
        assertEquals(1, server.requestCount)
    }

    @Test fun `后台只预取有效说法图片且已存在键盘离线打开即读取新目录`() = runBlocking {
        val foreground = sync()
        val background = sync()
        version("v2")
        catalog(document("v2", listOf(asset(), asset("unassigned")), listOf("new")))
        server.enqueue(MockResponse().setBody(String(bytes)))
        assertTrue(background.syncInBackground())
        assertEquals(3, server.requestCount)
        foreground.onKeyboardOpened(checkRemoteVersion = false).join()
        var results = emptyList<ExpressionAsset>()
        foreground.search("嘚瑟", 1, { true }, automatic = true) { results = it }.join()
        assertEquals(listOf("new"), results.map { it.id })
        assertTrue(results.single().resolvedPreviewUrl!!.startsWith("file://"))
        assertEquals("v2", foreground.currentCatalog().document.version)
        assertEquals(3, server.requestCount)
    }

    @Test fun `相同版本和已缓存原件只发一次轻量检查`() = runBlocking {
        val sync = sync()
        version("v2"); catalog(document("v2", listOf(asset())))
        server.enqueue(MockResponse().setBody(String(bytes)))
        assertTrue(sync.syncInBackground())
        version("v2")
        assertTrue(sync.syncInBackground())
        assertEquals(4, server.requestCount)
    }

    @Test fun `图片失败不会丢目录且版本不变也会下轮补齐`() = runBlocking {
        val sync = sync()
        version("v2"); catalog(document("v2", listOf(asset())))
        server.enqueue(MockResponse().setResponseCode(503))
        assertFalse(sync.syncInBackground())
        assertEquals("v2", sync.currentCatalog().document.version)
        version("v2"); server.enqueue(MockResponse().setBody(String(bytes)))
        assertTrue(sync().syncInBackground())
        assertEquals(5, server.requestCount)
    }

    @Test fun `目录失败保留最后有效目录`() = runBlocking {
        val sync = sync()
        version("v2"); server.enqueue(MockResponse().setResponseCode(503))
        assertFalse(sync.syncInBackground())
        assertEquals("apk", sync.currentCatalog().document.version)
        assertEquals(2, server.requestCount)
    }

    @Test fun `单轮下载上限之后从缺件继续而不重复已完成图片`() = runBlocking {
        val sync = sync()
        val secondBytes = "second-image".toByteArray()
        version("v2"); catalog(document("v2", listOf(asset(), asset("second", secondBytes))))
        server.enqueue(MockResponse().setBody(String(bytes)))
        assertFalse(sync.syncInBackground(maxDownloads = 1))
        assertEquals(3, server.requestCount)
        version("v2"); server.enqueue(MockResponse().setBody(String(secondBytes)))
        assertTrue(sync.syncInBackground(maxDownloads = 1))
        assertEquals(5, server.requestCount)
    }

    @Test fun `旧实例先读取其他实例已同步的目录不会重复拉取`() = runBlocking {
        val stale = sync()
        version("v2"); catalog(document("v2", listOf(asset())))
        server.enqueue(MockResponse().setBody(String(bytes)))
        assertTrue(sync().syncInBackground())
        version("v2")
        assertTrue(stale.syncInBackground())
        assertEquals(4, server.requestCount)
        assertEquals("v2", stale.currentCatalog().document.version)
    }

    @Test fun `相同图片ID内容更新后下载新SHA原件`() = runBlocking {
        val sync = sync()
        version("v2"); catalog(document("v2", listOf(asset())))
        server.enqueue(MockResponse().setBody(String(bytes)))
        assertTrue(sync.syncInBackground())
        val changed = "updated-image".toByteArray()
        version("v3"); catalog(document("v3", listOf(asset(data = changed))))
        server.enqueue(MockResponse().setBody(String(changed)))
        assertTrue(sync.syncInBackground())
        var results = emptyList<ExpressionAsset>()
        sync.search("嘚瑟", 1, { true }, automatic = true) { results = it }.join()
        val file = File(java.net.URI(results.single().resolvedPreviewUrl!!))
        assertArrayEquals(changed, file.readBytes())
        assertEquals(6, server.requestCount)
    }

    @Test fun `校验不符的下载不发布且下轮继续补齐`() = runBlocking {
        val sync = sync()
        version("v2"); catalog(document("v2", listOf(asset())))
        server.enqueue(MockResponse().setBody("corrupted"))
        assertFalse(sync.syncInBackground())
        assertNull(ExpressionCache(root).validFile(asset().version, asset().fileName, asset().sha256))
        version("v2"); server.enqueue(MockResponse().setBody(String(bytes)))
        assertTrue(sync.syncInBackground())
        assertNotNull(ExpressionCache(root).validFile(asset().version, asset().fileName, asset().sha256))
    }

    @Test fun `两个同步实例并发更新只拉一次目录`() = runBlocking {
        val catalogRequests = java.util.concurrent.atomic.AtomicInteger()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = when {
                request.path!!.endsWith("/versions") -> MockResponse().setBody("{\"version\":\"v2\"}")
                request.path!!.contains("/catalog") -> {
                    catalogRequests.incrementAndGet()
                    MockResponse().setBody(Json.encodeToString(document("v2", listOf(asset()))))
                        .setBodyDelay(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                }
                else -> MockResponse().setBody(String(bytes))
            }
        }
        val first = sync(); val second = sync()
        val results = listOf(async { first.syncInBackground() }, async { second.syncInBackground() }).awaitAll()
        assertTrue(results.all { it })
        assertEquals(1, catalogRequests.get())
        assertEquals("v2", second.currentCatalog().document.version)
    }

    @Test fun `缓存接近上限时后台不驱逐旧图也不反复下载`() = runBlocking {
        val originals = File(root, "expression-query/originals").apply { mkdirs() }
        val existing = File(originals, "a".repeat(64))
        java.io.RandomAccessFile(existing, "rw").use { it.setLength(55L * 1024 * 1024) }
        val sync = sync()
        version("v2"); catalog(document("v2", listOf(asset())))
        assertFalse(sync.syncInBackground())
        version("v2")
        assertFalse(sync.syncInBackground())
        assertEquals("缓存预算不足只检查版本，不重复请求图片", 3, server.requestCount)
        assertTrue(existing.isFile)
        assertEquals(55L * 1024 * 1024, existing.length())
    }

    @Test fun `后台网络等待时键盘仍立即展示已落盘目录`() = runBlocking {
        val background = sync()
        version("v2"); catalog(document("v2", listOf(asset())))
        server.enqueue(MockResponse().setBody(String(bytes)))
        assertTrue(background.syncInBackground())
        repeat(3) { server.takeRequest() }
        val foreground = sync()
        server.enqueue(MockResponse().setBody("{\"version\":\"v2\"}")
            .setBodyDelay(2, java.util.concurrent.TimeUnit.SECONDS))
        val waiting = async { background.syncInBackground() }
        withContext(Dispatchers.IO) { server.takeRequest() }
        var shown = false
        withTimeout(750) { foreground.onKeyboardOpened(checkRemoteVersion = false) { shown = true }.join() }
        assertTrue(shown)
        assertEquals("v2", foreground.currentCatalog().document.version)
        assertTrue(waiting.await())
    }

    @Test fun `被取消的旧响应读完后也不能覆盖新目录`() = runBlocking {
        val readComplete = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val client = OkHttpClient.Builder().dispatcher(okhttp3.Dispatcher(executor)).addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val original = response.body!!
            val source = object : okio.ForwardingSource(original.source()) {
                override fun read(sink: okio.Buffer, byteCount: Long): Long {
                    val count = super.read(sink, byteCount)
                    if (count == -1L) {
                        readComplete.countDown()
                        check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    }
                    return count
                }
            }.buffer()
            response.newBuilder().body(object : okhttp3.ResponseBody() {
                override fun contentType() = original.contentType()
                override fun contentLength() = original.contentLength()
                override fun source() = source
            }).build()
        }.build()
        try {
            val old = ExpressionSync(client, server.url("").toString().trimEnd('/'), "device",
                ExpressionCatalog(document("apk")), ExpressionCache(root), scope)
            catalog(document("v2", listOf(asset())))
            val oldRequest = async { old.refreshCatalog() }
            assertTrue(withContext(Dispatchers.IO) { readComplete.await(3, java.util.concurrent.TimeUnit.SECONDS) })
            oldRequest.cancelAndJoin()
            catalog(document("v3"))
            assertEquals("v3", sync().refreshCatalog().document.version)
            release.countDown()
            withContext(Dispatchers.IO) { executor.submit {}.get(3, java.util.concurrent.TimeUnit.SECONDS) }
            assertEquals("v3", sync().currentCatalog().document.version)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }
}
