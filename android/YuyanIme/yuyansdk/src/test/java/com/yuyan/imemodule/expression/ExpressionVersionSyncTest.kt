package com.yuyan.imemodule.expression

import com.yuyan.imemodule.expression.model.ExpressionAsset
import com.yuyan.imemodule.expression.model.ExpressionCatalogDocument
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ExpressionVersionSyncTest {
    private lateinit var server: MockWebServer
    private lateinit var root: File
    private lateinit var scope: CoroutineScope
    private val bytes = "new-private-gif".toByteArray()
    private val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun asset(id: String = "new") = ExpressionAsset(id, "prebuilt", "gif", sha,
        "stickers/$id.gif", sha256 = sha, width = 240, height = 240, keywords = listOf("干嘛"),
        url = "/uploads/stickers/$id.gif", distribution = "remote", sourceType = "owner-upload")
    private fun document(version: String, assets: List<ExpressionAsset> = emptyList()) =
        ExpressionCatalogDocument(version, assets, emptyList(), emptyList(), complete = true)
    private fun sync(user: String = "user-a", initial: ExpressionCatalogDocument = document("apk")) = ExpressionSync(
        OkHttpClient(), server.url("").toString().trimEnd('/'), user, ExpressionCatalog(initial), ExpressionCache(root), scope)
    @Before fun setup() {
        root = java.nio.file.Files.createTempDirectory("version-sync").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        server = MockWebServer().apply { start(); (dispatcher as okhttp3.mockwebserver.QueueDispatcher).setFailFast(true) }
    }
    @After fun cleanup() { scope.cancel(); runCatching { server.shutdown() }; root.deleteRecursively() }
    private suspend fun search(sync: ExpressionSync): List<ExpressionAsset> {
        var result = emptyList<ExpressionAsset>()
        sync.search("干嘛", 1, { true }) { result = it }.join()
        return result
    }
    @Test fun `慢图不能阻止后面的新图先显示且最终保持后台顺序`() = runBlocking {
        val slowBytes = "slow-upload".toByteArray()
        val slowSha = MessageDigest.getInstance("SHA-256").digest(slowBytes).joinToString("") { "%02x".format(it) }
        val slow = asset("slow").copy(sha256 = slowSha, version = slowSha)
        val fast = asset("fast")
        val slowStarted = java.util.concurrent.CountDownLatch(1)
        val releaseSlow = java.util.concurrent.CountDownLatch(1)
        val fastVisible = java.util.concurrent.CountDownLatch(1)
        val seen = java.util.concurrent.CopyOnWriteArrayList<List<String>>()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                if (request.path!!.contains("slow.gif")) {
                    slowStarted.countDown(); releaseSlow.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    return MockResponse().setBody(okio.Buffer().write(slowBytes))
                }
                return MockResponse().setBody(okio.Buffer().write(bytes))
            }
        }
        val job = sync(initial = document("apk", listOf(slow, fast)))
            .search("干嘛", 1, { true }, automatic = true) { results ->
                val ids = results.map { it.id }; seen += ids
                if (ids == listOf("fast")) fastVisible.countDown()
            }
        try {
            assertTrue(slowStarted.await(2, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("慢图尚未完成时必须先展示已下载的快图", fastVisible.await(2, java.util.concurrent.TimeUnit.SECONDS))
        } finally { releaseSlow.countDown(); job.join() }
        assertEquals(listOf("slow", "fast"), seen.last())
        assertEquals(2, server.requestCount)
    }

    @Test fun `有持续传输的大图超过30秒仍能完整校验入库`() = runBlocking {
        // 每秒持续传一个字节，总时长31秒，验证整个响应体而非仅响应头。
        val body = "x".repeat(32).toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
        val image = asset().copy(version = hash, sha256 = hash)
        server.enqueue(MockResponse().setBody(okio.Buffer().write(body))
            .throttleBody(1, 1, java.util.concurrent.TimeUnit.SECONDS))
        val file = sync(initial = document("apk", listOf(image)))
            .download(image.version, image.fileName, image.url!!, hash)
        assertNotNull("持续下载不能被目录接口的30秒总超时截断", file)
        assertArrayEquals(body, file!!.readBytes())
    }

    @Test fun `目录排序变更后旧异步搜索不得再次发布`() = runBlocking {
        val first = asset("first").copy(distribution = "bundled", url = null)
        val second = asset("second").copy(distribution = "bundled", url = null)
        val sync = sync(initial = document("apk", listOf(first, second)))
        server.enqueue(MockResponse().setBody(Json.encodeToString(document("v2", listOf(second, first)))))
        var callbacks = 0
        sync.search("干嘛", 1, { true }) {
            callbacks++
            if (callbacks == 1) runBlocking { sync.refreshCatalog() }
        }.join()
        assertEquals("目录切换后旧批次不得再覆盖新顺序", 1, callbacks)
    }

    @Test fun `自动查询不使用旧查询缓存召回已删除说法`() = runBlocking {
        val bundled = asset().copy(distribution = "bundled", url = null)
        val cache = ExpressionCache(root)
        val baseUrl = server.url("").toString().trimEnd('/')
        ExpressionQueryCache(cache).write(baseUrl, "干嘛", listOf(ExpressionQueryCache.Item(bundled, "owner-upload")))
        val initial = document("apk", listOf(bundled)).copy(
            recommendationGroups = listOf(com.yuyan.imemodule.expression.model.ExpressionRecommendationGroup(
                "干嘛", listOf("你好"), listOf(bundled.id))),
        )
        val sync = sync(initial = initial)
        val seen = mutableListOf<ExpressionAsset>()
        sync.search("干嘛", 1, { true }, automatic = true) { seen += it }.join()
        assertTrue(seen.isEmpty())
        assertEquals(0, server.requestCount)
        sync.search("你好", 2, { true }, automatic = true) { seen += it }.join()
        assertTrue(seen.isNotEmpty())
    }

    @Test fun `完整配置的长说法不受旧手动查询100字上限拦截`() = runBlocking {
        val phrase = "完整说法".repeat(30)
        val bundled = asset().copy(distribution = "bundled", url = null)
        val initial = document("apk", listOf(bundled)).copy(
            recommendationGroups = listOf(com.yuyan.imemodule.expression.model.ExpressionRecommendationGroup(
                "长说法", listOf(phrase), listOf(bundled.id))),
        )
        val results = mutableListOf<ExpressionAsset>()
        sync(initial = initial).search(phrase, 1, { true }, automatic = true) { results += it }.join()
        assertTrue(results.isNotEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test fun `系统带字精确命中不能遮蔽个人同词关键词图`() {
        val system = asset("system").copy(sourceType = "ai-original", embeddedText = "干嘛")
        val personal = asset("personal")
        val catalog = ExpressionCatalog(document("apk", listOf(system, personal)))
        assertEquals(setOf("system", "personal"), catalog.recommend("干嘛").map { it.id }.toSet())
        assertNull(catalog.recommend("干嘛").first { it.id == "personal" }.embeddedText)
    }

    @Test fun `同次打开只检查一次版本且连续输入零网络`() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"version\":\"apk\"}"))
        val sync = sync()
        sync.onKeyboardOpened().join(); sync.onKeyboardOpened().join()
        repeat(3) { search(sync) }
        assertEquals(1, server.requestCount)
        assertEquals("/api/v1/mobile/expressions/versions", server.takeRequest().path)
        sync.onKeyboardClosed()
        server.enqueue(MockResponse().setBody("{\"version\":\"apk\"}"))
        sync.onKeyboardOpened().join()
        assertEquals(2, server.requestCount)
    }
    @Test fun `变更拉完整目录仅新SHA下载且重启缓存离线可检索`() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"version\":\"v2\"}"))
        server.enqueue(MockResponse().setBody(Json.encodeToString(document("v2", listOf(asset())))))
        server.enqueue(MockResponse().setBody(String(bytes)))
        val sync = sync()
        sync.onKeyboardOpened().join()
        assertEquals(listOf("new"), search(sync).map { it.id })
        assertEquals(3, server.requestCount)
        repeat(3) { search(sync) }
        assertEquals(3, server.requestCount)
        val restarted = sync()
        assertEquals("v2", restarted.currentCatalog().document.version)
        assertEquals(listOf("new"), search(restarted).map { it.id })
        assertEquals(3, server.requestCount)
        server.shutdown()
        restarted.onKeyboardOpened().join()
        assertEquals(listOf("new"), search(restarted).map { it.id })
    }
    @Test fun `权威删除不会merge复活且私有元数据不跨用户`() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"version\":\"v2\"}"))
        server.enqueue(MockResponse().setBody(Json.encodeToString(document("v2", listOf(asset())))))
        server.enqueue(MockResponse().setBody(String(bytes)))
        val sync = sync(); sync.onKeyboardOpened().join(); search(sync)
        assertTrue(sync("user-b").currentCatalog().document.templates.isEmpty())
        sync.onKeyboardClosed()
        server.enqueue(MockResponse().setBody("{\"version\":\"v3\"}"))
        server.enqueue(MockResponse().setBody(Json.encodeToString(document("v3"))))
        sync.onKeyboardOpened().join()
        assertTrue(search(sync).isEmpty())
        assertTrue(sync().currentCatalog().document.templates.isEmpty())
        assertEquals(5, server.requestCount)
    }
    @Test fun `内置SHA相同无需因单素材版本变为SHA而下载`() = runBlocking {
        val bundled = asset().copy(version = "apk", distribution = "bundled", sourceType = "ai-original", url = null)
        val sync = sync(initial = document("apk", listOf(bundled)))
        server.enqueue(MockResponse().setBody("{\"version\":\"v2\"}"))
        server.enqueue(MockResponse().setBody(Json.encodeToString(document("v2", listOf(bundled.copy(version = sha))))))
        sync.onKeyboardOpened().join()
        assertEquals(listOf("new"), search(sync).map { it.id })
        assertEquals(2, server.requestCount)
    }
    @Test fun `目录变化仅新增SHA下载已有SHA改ID和版本仍复用`() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"version\":\"v2\"}"))
        server.enqueue(MockResponse().setBody(Json.encodeToString(document("v2", listOf(asset())))))
        server.enqueue(MockResponse().setBody(String(bytes)))
        val sync = sync(); sync.onKeyboardOpened().join(); search(sync)
        val nextBytes = "second-gif".toByteArray()
        val nextSha = MessageDigest.getInstance("SHA-256").digest(nextBytes).joinToString("") { "%02x".format(it) }
        val old = asset("renamed").copy(version = "metadata-new")
        val next = asset("second").copy(sha256 = nextSha, version = nextSha)
        sync.onKeyboardClosed()
        server.enqueue(MockResponse().setBody("{\"version\":\"v3\"}"))
        server.enqueue(MockResponse().setBody(Json.encodeToString(document("v3", listOf(old, next)))))
        server.enqueue(MockResponse().setBody(String(nextBytes)))
        sync.onKeyboardOpened().join()
        assertEquals(setOf("renamed", "second"), search(sync).map { it.id }.toSet())
        assertEquals(6, server.requestCount)
        assertEquals(listOf("/api/v1/mobile/expressions/versions", "/api/v1/mobile/expressions/catalog?version=apk", "/uploads/stickers/new.gif",
            "/api/v1/mobile/expressions/versions", "/api/v1/mobile/expressions/catalog?version=v2", "/uploads/stickers/second.gif"),
            List(6) { server.takeRequest().path })
    }

    @Test fun `目录持久化还按APK版本及服务端隔离`() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"version\":\"v2\"}"))
        server.enqueue(MockResponse().setBody(Json.encodeToString(document("v2", listOf(asset())))))
        sync().onKeyboardOpened().join()
        assertTrue(sync(initial = document("new-apk")).currentCatalog().document.templates.isEmpty())
        val other = ExpressionSync(OkHttpClient(), "http://127.0.0.1:1", "user-a", ExpressionCatalog(document("apk")), ExpressionCache(root), scope)
        assertTrue(other.currentCatalog().document.templates.isEmpty())
    }

    @Test fun `缺失损坏原件只补下载不重查目录`() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"version\":\"v2\"}"))
        server.enqueue(MockResponse().setBody(Json.encodeToString(document("v2", listOf(asset())))))
        server.enqueue(MockResponse().setBody(String(bytes)))
        val sync = sync(); sync.onKeyboardOpened().join(); search(sync)
        val file = requireNotNull(ExpressionCache(root).validFile(sha, asset().fileName, sha))
        file.writeText("corrupt")
        server.enqueue(MockResponse().setBody(String(bytes)))
        assertEquals(listOf("new"), search(sync).map { it.id })
        assertEquals(4, server.requestCount)
        assertEquals(String(bytes), requireNotNull(ExpressionCache(root).validFile(sha, asset().fileName, sha)).readText())
    }

    @Test fun `完整目录同SHA跨查询并发只取一次取消订阅仍落盘`() = runBlocking {
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                started.countDown(); release.await(3, java.util.concurrent.TimeUnit.SECONDS)
                return MockResponse().setBody(String(bytes))
            }
        }
        val sync = sync(initial = document("apk", listOf(asset().copy(keywords = listOf("干嘛", "你好")))))
        val first = sync.search("干嘛", 1, { true }) { }
        assertTrue(started.await(3, java.util.concurrent.TimeUnit.SECONDS))
        first.cancel()
        var result = emptyList<ExpressionAsset>()
        val next = sync.search("你好", 2, { true }) { result = it }
        delay(100)
        assertEquals(1, server.requestCount)
        release.countDown(); next.join()
        assertEquals(listOf("new"), result.map { it.id })
        assertEquals(1, server.requestCount)
    }

    @Test fun `慢下载期间权威删除不能迟到复活旧推荐`() = runBlocking {
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse =
                if (request.path!!.startsWith("/uploads/")) {
                    started.countDown(); release.await(3, java.util.concurrent.TimeUnit.SECONDS)
                    MockResponse().setBody(String(bytes))
                } else MockResponse().setBody(Json.encodeToString(document("deleted")))
        }
        val sync = sync(initial = document("apk", listOf(asset())))
        var result = emptyList<ExpressionAsset>()
        val query = sync.search("干嘛", 1, { true }) { result = it }
        assertTrue(started.await(3, java.util.concurrent.TimeUnit.SECONDS))
        sync.refreshCatalog()
        release.countDown(); query.join()
        assertTrue(result.isEmpty())
        assertTrue(sync.currentCatalog().document.templates.isEmpty())
    }

    @Test fun `不同ID和路径但同SHA复用APK真实原件路径`() = runBlocking {
        val builtIn = asset("builtin").copy(fileName = "templates/builtin.gif", distribution = "bundled", sourceType = "ai-original", url = null)
        val sync = sync(initial = document("apk", listOf(builtIn)))
        server.enqueue(MockResponse().setBody("{\"version\":\"v2\"}"))
        server.enqueue(MockResponse().setBody(Json.encodeToString(document("v2", listOf(asset("alias"))))))
        sync.onKeyboardOpened().join()
        val result = search(sync).single()
        assertEquals("alias", result.id)
        assertEquals("templates/builtin.gif", result.fileName)
        assertNull(result.url)
        assertEquals(2, server.requestCount)
    }

    @Test fun `推荐允许2MiB以上但10MiB以内原件并按SHA缓存`() = runBlocking {
        val big = ByteArray(2 * 1024 * 1024 + 1) { 42 }
        val digest = MessageDigest.getInstance("SHA-256").digest(big).joinToString("") { "%02x".format(it) }
        val value = asset().copy(sha256 = digest, version = digest)
        val sync = sync(initial = document("apk", listOf(value)))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(big)))
        assertEquals(listOf("new"), search(sync).map { it.id })
        assertEquals(1, server.requestCount)
    }

    @Test fun `完整APK遇到旧不完整目录不能降级到逐词联网`() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"version\":\"legacy\"}"))
        server.enqueue(MockResponse().setBody(Json.encodeToString(document("legacy").copy(complete = false))))
        val sync = sync(); sync.onKeyboardOpened().join()
        assertTrue(sync.currentCatalog().document.complete)
        assertEquals("apk", sync.currentCatalog().document.version)
        repeat(3) { search(sync) }
        assertEquals(2, server.requestCount)
    }

    @Test fun `合成GIF未知内容长度允许超过旧250KiB门槛`() = runBlocking {
        val large = ByteArray(250 * 1024 + 1) { 42 }
        val digest = MessageDigest.getInstance("SHA-256").digest(large).joinToString("") { "%02x".format(it) }
        val value = asset().copy(type = "synthesis-template", sha256 = digest, version = digest)
        val sync = sync(initial = document("apk", listOf(value)))
        server.enqueue(MockResponse().setChunkedBody(okio.Buffer().write(large), 1024))
        assertNotNull(sync.download(digest, value.fileName, requireNotNull(value.url), digest))
        assertNotNull(ExpressionCache(root).validFile(digest, value.fileName, digest))
        assertEquals(1, server.requestCount)
    }

    @Test fun `版本接口迟迟未返回时缓存的个人AI底图先可预览`() = runBlocking {
        val release = java.util.concurrent.CountDownLatch(1)
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                release.await(3, java.util.concurrent.TimeUnit.SECONDS)
                return MockResponse().setBody("{\"version\":\"apk\"}")
            }
        }
        val value = asset().copy(type = "synthesis-template")
        ExpressionQueryCache(ExpressionCache(root)).writeOriginal(sha, bytes.inputStream())
        val sync = sync(initial = document("apk", listOf(value)))
        val ready = CompletableDeferred<Unit>()
        val job = sync.onKeyboardOpened {
            if (sync.currentCatalog().document.templates.single().resolvedPreviewUrl?.startsWith("file://") == true) ready.complete(Unit)
        }
        try { withTimeout(1000) { ready.await() } }
        finally { release.countDown(); job.join() }
        assertEquals(1, server.requestCount)
        assertEquals("/api/v1/mobile/expressions/versions", server.takeRequest().path)
    }

    @Test fun `同窗口重建跳过版本但仍恢复缓存底图真实下次打开再检查`() = runBlocking {
        val value = asset().copy(type = "synthesis-template")
        ExpressionQueryCache(ExpressionCache(root)).writeOriginal(sha, bytes.inputStream())
        val sync = sync(initial = document("apk", listOf(value)))
        sync.onKeyboardOpened(checkRemoteVersion = false).join()
        assertTrue(sync.currentCatalog().document.templates.single().resolvedPreviewUrl!!.startsWith("file://"))
        assertEquals(0, server.requestCount)
        sync.onKeyboardClosed()
        server.enqueue(MockResponse().setBody("{\"version\":\"apk\"}"))
        sync.onKeyboardOpened().join()
        assertEquals(1, server.requestCount)
    }

}
