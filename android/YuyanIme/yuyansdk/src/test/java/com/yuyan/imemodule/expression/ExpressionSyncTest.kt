package com.yuyan.imemodule.expression

import com.yuyan.imemodule.expression.model.ExpressionAsset
import com.yuyan.imemodule.expression.model.ExpressionCatalogDocument
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
        server = MockWebServer().apply { start() }
        root = createTempDir(prefix = "expression-sync-")
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
        assertEquals(listOf("fallback"), refreshed.search("任意").map { it.id })
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
    fun `先同步返回本地结果且过期响应不会发布`() = runBlocking {
        val local = ExpressionCatalog(
            document("v1", listOf(asset("local", type = "prebuilt", embeddedText = "放箭"))),
        )
        server.enqueue(MockResponse().setBody("""{"results":[${json.encodeToString(asset("remote"))}]}"""))
        val seen = mutableListOf<List<String>>()
        val sync = sync(local, this)

        val job = sync.search("放箭", requestId = 7, acceptResponse = { false }) { results ->
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
                """{"results":${json.encodeToString(remoteAssets)}}""",
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
    fun `取消搜索会终止底层 HTTP 调用`() {
        val client = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val sync = sync(ExpressionCatalog(document()), scope, client = client)

        val job = sync.search("放箭", requestId = 1, acceptResponse = { true }) { }
        requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        job.cancel()
        Thread.sleep(100)

        assertEquals(0, client.dispatcher.runningCallsCount())
        scope.cancel()
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
        sha256 = id.padEnd(64, 'c').take(64),
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
