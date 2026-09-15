package com.yuyan.imemodule.expression

import com.yuyan.imemodule.expression.model.ExpressionAsset
import com.yuyan.imemodule.expression.model.ExpressionCatalogDocument
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ExpressionSyncTest {
    private lateinit var server: MockWebServer
    private lateinit var root: File
    private val json = Json { ignoreUnknownKeys = true }
    private val deviceId = "00000000-0000-4000-8000-000000000001"

    @Before
    fun setUp() {
        server = MockWebServer().apply { start(); (dispatcher as okhttp3.mockwebserver.QueueDispatcher).setFailFast(true) }
        root = java.nio.file.Files.createTempDirectory("expression-sync-").toFile()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
        root.deleteRecursively()
    }

    @Test
    fun `服务端目录增量覆盖本地版本`() = runBlocking {
        val local = ExpressionCatalog(document("v1", listOf(asset("shared", heat = 1))))
        val remote = document(
            "v2",
            listOf(
                asset("shared", version = "v2", type = "prebuilt", embeddedText = "你好", heat = 9),
                asset("new"),
            ),
        )
        server.enqueue(MockResponse().setBody(json.encodeToString(remote)))
        val sync = sync(local, this)

        val refreshed = sync.refreshCatalog()

        assertEquals("v2", refreshed.document.version)
        assertEquals(9, refreshed.document.templates.first { it.id == "shared" }.heat)
        assertEquals("prebuilt", refreshed.document.templates.first { it.id == "shared" }.type)
        assertEquals("你好", refreshed.document.templates.first { it.id == "shared" }.embeddedText)
        assertEquals("v1", server.takeRequest().requestUrl?.queryParameter("version"))
    }

    @Test
    fun `断网时安全回退本地目录`() = runBlocking {
        val local = ExpressionCatalog(
            document(
                "offline-v1",
                listOf(
                    asset("hello", type = "prebuilt", embeddedText = "你好"),
                    asset("fallback", type = "synthesis-template"),
                ),
            ),
        )
        server.shutdown()

        val refreshed = sync(local, this).refreshCatalog()

        assertEquals("offline-v1", refreshed.document.version)
        assertEquals(listOf("hello"), refreshed.search("你好").map { it.id })
        assertEquals(emptyList<String>(), refreshed.search("任意").map { it.id })
    }

    @Test
    fun `损坏下载不会替换有效缓存`() = runBlocking {
        val cache = ExpressionCache(root)
        val validBytes = "valid-image".toByteArray()
        val expectedSha = sha256(validBytes)
        val existing = cache.writeVerified("v1", "templates/exact.webp", expectedSha, validBytes.inputStream())
        server.enqueue(MockResponse().setBody("corrupt-image"))
        val sync = sync(ExpressionCatalog(document()), this, cache)

        val result = sync.download(
            version = "v1",
            relativePath = "templates/exact.webp",
            url = server.url("/uploads/expression/templates/exact.webp").toString(),
            sha256 = expectedSha,
        )

        assertEquals(existing, result)
        assertEquals("valid-image", result?.readText())
        assertTrue(result?.parentFile?.listFiles()?.none { it.extension == "part" } == true)
    }

    @Test
    fun `下载会将无前导斜杠的远端相对地址拼到服务端`() = runBlocking {
        val bytes = "remote-image".toByteArray()
        server.enqueue(MockResponse().setBody(bytes.toString(Charsets.UTF_8)))
        val sync = sync(ExpressionCatalog(document()), this)

        val result = sync.download(
            version = "v1",
            relativePath = "search-cache/remote.webp",
            url = "uploads/expression/search-cache/remote.webp",
            sha256 = sha256(bytes),
        )

        assertEquals("remote-image", result?.readText())
        assertEquals("/uploads/expression/search-cache/remote.webp", server.takeRequest().path)
    }

    @Test
    fun `下载保留大写 HTTP scheme 的绝对地址`() = runBlocking {
        val bytes = "uppercase-image".toByteArray()
        server.enqueue(MockResponse().setBody(bytes.toString(Charsets.UTF_8)))
        val sync = sync(ExpressionCatalog(document()), this)
        val absolute = server.url("/uploads/uppercase.webp").toString().replaceFirst("http://", "HTTP://")

        val result = sync.download(
            version = "v1",
            relativePath = "search-cache/uppercase.webp",
            url = absolute,
            sha256 = sha256(bytes),
        )

        assertEquals("uppercase-image", result?.readText())
        assertEquals("/uploads/uppercase.webp", server.takeRequest().path)
    }

    @Test
    fun `先同步返回本地结果且过期响应不会发布`() = runBlocking {
        val local = ExpressionCatalog(
            document("v1", listOf(asset("local", type = "prebuilt", embeddedText = "放箭"))),
        )
        server.enqueue(MockResponse().setBody("""{"results":[${wireAsset(asset("remote"))}]}"""))
        val seen = mutableListOf<List<String>>()
        val sync = sync(local, this)

        var responseChecks = 0
        val job = sync.search("放箭", requestId = 7, acceptResponse = { ++responseChecks == 1 }) { results ->
            seen += results.map { it.id }
        }
        job.join()

        assertEquals(listOf(listOf("local")), seen)
    }

    @Test
    fun `远端结果与离线目录使用同一两级推荐策略`() = runBlocking {
        val localAssets = listOf(
            asset("local-hello", type = "prebuilt", embeddedText = "你好"),
            asset("local-template", type = "synthesis-template"),
        )
        val remoteAssets = listOf(
            asset("remote-template", type = "synthesis-template", heat = 100),
            asset("remote-other", type = "prebuilt", embeddedText = "再见", heat = 90),
            asset("remote-hello-low", type = "prebuilt", embeddedText = "你好", heat = 1),
            asset("remote-hello-hot", type = "prebuilt", embeddedText = "你好", heat = 9),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"results":${wireAssets(remoteAssets)}}""",
            ),
        )
        val seen = mutableListOf<List<String>>()

        val job = sync(ExpressionCatalog(document("v1", localAssets)), this)
            .search("你好", requestId = 8, acceptResponse = { it == 8L }) { results ->
                seen += results.map { it.id }
            }
        job.join()

        assertEquals(
            listOf(
                listOf("local-hello"),
                listOf("remote-hello-hot", "remote-hello-low"),
            ),
            seen,
        )
    }

    @Test
    fun `长句远端结果重新排序仍保留相关固定字预制图`() = runBlocking {
        val localAssets = listOf(
            asset("local-hello", type = "prebuilt", embeddedText = "你好"),
            asset("local-template", type = "synthesis-template"),
        )
        val remoteAssets = listOf(
            asset("remote-template", type = "synthesis-template", heat = 100),
            asset("remote-other", type = "prebuilt", embeddedText = "再见", heat = 90),
            asset("remote-hello-low", type = "prebuilt", embeddedText = "你好", heat = 1),
            asset("remote-hello-hot", type = "prebuilt", embeddedText = "你好", heat = 9),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"results":${wireAssets(remoteAssets)}}""",
            ),
        )
        val seen = mutableListOf<List<String>>()

        val job = sync(ExpressionCatalog(document("v1", localAssets)), this)
            .search("你好呀朋友", requestId = 8, acceptResponse = { it == 8L }) { results ->
                seen += results.map { it.id }
            }
        job.join()

        assertEquals(
            listOf(
                listOf("local-hello"),
                listOf("remote-hello-hot", "remote-hello-low"),
            ),
            seen,
        )
    }

    @Test
    fun `取消订阅保留预取但销毁所属scope会终止底层HTTP`() {
        val client = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val sync = sync(ExpressionCatalog(document()), scope, client = client)

        val job = sync.search("放箭", requestId = 1, acceptResponse = { true }) { }
        requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        job.cancel()
        Thread.sleep(100)
        assertEquals(1, client.dispatcher.runningCallsCount())
        scope.cancel()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (client.dispatcher.runningCallsCount() != 0 && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(0, client.dispatcher.runningCallsCount())
    }

    @Test
    fun `本地与远端结果都串行回到订阅scope调度器`() {
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "expression-ui") }
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val callbacks = CountDownLatch(2)
        val callbackThreads = mutableListOf<String>()
        server.enqueue(MockResponse().setBody("""{"results":[${wireAsset(asset("remote"))}]}"""))

        val job = sync(
            ExpressionCatalog(document("v1", listOf(asset("local", type = "prebuilt", embeddedText = "你好")))),
            scope,
        ).search("你好", 90, acceptResponse = { true }) {
            synchronized(callbackThreads) { callbackThreads += Thread.currentThread().name }
            callbacks.countDown()
        }

        assertTrue(callbacks.await(3, TimeUnit.SECONDS))
        runBlocking { job.join() }
        assertEquals(2, callbackThreads.size)
        assertTrue(callbackThreads.all { it.startsWith("expression-ui") })
        assertEquals(1, callbackThreads.distinct().size)
        scope.cancel()
        dispatcher.close()
        executor.shutdownNow()
    }

    @Test
    fun `完整内置索引首次只发布四张内置和校验有效远端缓存`() = runBlocking {
        val bytes = "cached-gif".toByteArray()
        val bundled = (1..4).map { asset("bundled-$it", type = "prebuilt", embeddedText = "你好").copy(format = "gif") }
        val remote = (1..4).map { remoteAsset("remote-$it", sha256(bytes)).copy(thumbnailFileName = "thumbnails/remote-$it.webp") }
        val cache = ExpressionCache(root)
        val cached = requireNotNull(cache.writeVerified("v1", remote[0].fileName, remote[0].sha256, bytes.inputStream()))
        cache.file("v1", remote[1].fileName).apply { parentFile?.mkdirs(); writeText("corrupt") }
        server.enqueue(MockResponse().setResponseCode(503))
        val seen = mutableListOf<List<ExpressionAsset>>()
        sync(ExpressionCatalog(document("v1", bundled + remote)), this, cache)
            .search("你好", 1, { true }) { seen += it }.join()

        assertEquals(bundled.map { it.id } + remote[0].id, seen.single().map { it.id })
        assertEquals("file://${cached.absolutePath}", seen.single().last().resolvedPreviewUrl)
        assertEquals("file:///android_asset/expression/thumbnails/remote-1.webp", seen.single().last().thumbnailUrl)
    }

    @Test
    fun `远端原GIF下载后新Sync依靠完整APK索引断网复用且损坏后不再推荐`() = runBlocking {
        val bytes = File("../../../artifacts/expression-batches/daily-01/gifs/hello-plush-rabbit.gif").readBytes()
        assertEquals(16, com.bumptech.glide.gifdecoder.GifHeaderParser().setData(bytes).parseHeader().numFrames)
        val remote = remoteAsset("remote-real", sha256(bytes))
        val initial = ExpressionCatalog(document("v1", listOf(remote)))
        val firstCache = ExpressionCache(root)
        server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
        val first = sync(initial, this, firstCache)
        val downloaded = requireNotNull(first.download("v1", remote.fileName, server.url("/original.gif").toString(), remote.sha256))
        assertTrue(bytes.contentEquals(downloaded.readBytes()))
        assertEquals("/original.gif", server.takeRequest().path)
        server.shutdown()
        val cache = ExpressionCache(root)
        val restarted = sync(initial, this, cache)
        val seen = mutableListOf<List<ExpressionAsset>>()
        restarted.search("你好", 1, { true }) { seen += it }.join()
        val visible = seen.single().single()
        assertEquals("file://${downloaded.absolutePath}", visible.resolvedPreviewUrl)
        val sendSource = ExpressionAssetResolver(cache, { error("必须缓存命中") }, { _, _, _, _ -> error("不能联网") })
            .resolve(remote.version, remote.fileName, remote.sha256, remote.url)
        assertTrue(bytes.contentEquals(requireNotNull(sendSource).readBytes()))
        downloaded.writeText("broken")
        val corruptSeen = mutableListOf<List<ExpressionAsset>>()
        sync(initial, this, ExpressionCache(root)).search("你好", 2, { true }) { corruptSeen += it }.join()
        assertTrue(corruptSeen.single().isEmpty())
    }

    @Test
    fun `本地IO校验后已经过期的请求也不能发布结果`() = runBlocking {
        val local = ExpressionCatalog(document("v1", listOf(asset("hello", type = "prebuilt", embeddedText = "你好"))))
        server.enqueue(MockResponse().setResponseCode(503))
        val seen = mutableListOf<List<ExpressionAsset>>()
        sync(local, this).search("你好", 1, { false }) { seen += it }.join()
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `远端推荐只有APK已知缩略图使用本地回退未来新增条目保留网络回退`() = runBlocking {
        val known = remoteAsset("known", "a".repeat(64)).copy(thumbnailFileName = "thumbnails/known.webp")
        val remoteKnown = known.copy(url = "/uploads/known.gif", thumbnailUrl = "/uploads/known.webp")
        val future = remoteAsset("future", "b".repeat(64)).copy(
            thumbnailFileName = "thumbnails/future.webp", url = "/uploads/future.gif", thumbnailUrl = "/uploads/future.webp",
        )
        server.enqueue(MockResponse().setBody("""{"results":${wireAssets(listOf(remoteKnown, future))}}"""))
        val seen = mutableListOf<List<ExpressionAsset>>()
        sync(ExpressionCatalog(document("v1", listOf(known))), this).search("你好", 1, { true }) { seen += it }.join()
        assertTrue(seen.first().isEmpty())
        val online = seen.last().associateBy { it.id }
        assertEquals("file:///android_asset/expression/thumbnails/known.webp", online.getValue("known").thumbnailUrl)
        assertEquals("/uploads/future.webp", online.getValue("future").thumbnailUrl)
    }

    private fun wireAsset(asset: ExpressionAsset): String =
        json.encodeToString(asset).dropLast(1) + ",\"sourceType\":\"ai-original\"}"

    private fun wireAssets(assets: List<ExpressionAsset>): String = assets.joinToString(",", "[", "]", transform = ::wireAsset)

    private fun remoteAsset(id: String, sha: String): ExpressionAsset {
        val original = asset(id, type = "prebuilt", embeddedText = "你好").copy(
            format = "gif", fileName = "templates/$id.gif", sha256 = sha,
        )
        val encoded = json.encodeToString(original).dropLast(1) + ",\"distribution\":\"remote\"}"
        return json.decodeFromString(encoded)
    }

    private fun sync(
        catalog: ExpressionCatalog,
        scope: kotlinx.coroutines.CoroutineScope,
        cache: ExpressionCache = ExpressionCache(root),
        client: OkHttpClient = OkHttpClient(),
    ) = ExpressionSync(
        client = client,
        baseUrl = server.url("/").toString().removeSuffix("/"),
        deviceId = deviceId,
        initialCatalog = catalog,
        cache = cache,
        scope = scope,
    )

    private fun asset(
        id: String,
        version: String = "v1",
        type: String = "synthesis-template",
        embeddedText: String? = null,
        keywords: List<String> = emptyList(),
        heat: Long = 0,
    ) = ExpressionAsset(
        id = id,
        type = type,
        format = "webp",
        version = version,
        fileName = "templates/$id.webp",
        thumbnailFileName = null,
        sha256 = sha256(id.toByteArray()),
        width = 512,
        height = 512,
        keywords = keywords,
        emotions = emptyList(),
        embeddedText = embeddedText,
        heat = heat,
    )

    private fun document(
        version: String = "v1",
        assets: List<ExpressionAsset> = emptyList(),
    ) = ExpressionCatalogDocument(
        version = version,
        templates = assets,
        emojiBases = emptyList(),
        emojiCombinations = emptyList(),
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
