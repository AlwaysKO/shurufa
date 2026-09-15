package com.yuyan.imemodule.expression

import com.yuyan.imemodule.expression.model.ExpressionAsset
import com.yuyan.imemodule.expression.model.ExpressionCatalogDocument
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.TimeUnit
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ExpressionSync(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val deviceId: String,
    initialCatalog: ExpressionCatalog,
    private val cache: ExpressionCache,
    private val scope: CoroutineScope,
) {
    @Volatile
    private var catalog = initialCatalog
    private val json = Json { ignoreUnknownKeys = true }
    // 生成链保证初始 APK 目录的缩略图全部内置；之后新增的远端目录不能冒充本地资源。
    private val bundledThumbnails = initialCatalog.document.templates
        .mapNotNull { asset -> asset.thumbnailFileName?.let { asset.id to it } }.toMap()

    fun currentCatalog(): ExpressionCatalog = catalog

    suspend fun refreshCatalog(): ExpressionCatalog = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/api/v1/mobile/expressions/catalog"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("version", catalog.document.version)
                .build()
            val request = Request.Builder()
                .url(url)
                .header("X-Device-Id", deviceId)
                .build()
            client.newCall(request).awaitResponse().use { response ->
                if (response.code == 304) return@withContext catalog
                check(response.isSuccessful) { "catalog request failed: ${response.code}" }
                val remote = json.decodeFromString<ExpressionCatalogDocument>(
                    response.body?.string().orEmpty(),
                )
                catalog = catalog.merge(remote)
                catalog
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            catalog
        }
    }

    private val queryCache = ExpressionQueryCache(cache)
    private val trustedBundled = initialCatalog.document.templates.associateBy { it.id }
    private val networkClient = client.newBuilder().callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    private val pendingLock = Any()
    private val queries = mutableMapOf<String, QueryWork>()
    private val downloads = mutableMapOf<String, Deferred<File?>>()
    private val downloadSlots = Semaphore(2)
    private class QueryWork(val result: CompletableDeferred<List<ExpressionAsset>?>)

    fun search(
        query: String,
        requestId: Long,
        acceptResponse: (Long) -> Boolean,
        onResult: (List<ExpressionAsset>) -> Unit,
    ): Job = scope.launch {
        val normalized = ExpressionQueryMatching.normalize(query)
        if (normalized.isEmpty() || normalized.length > 100) return@launch
        val (entry, local, complete) = withContext(Dispatchers.IO) {
            val entry = queryCache.read(baseUrl, normalized)
            val cached = entry?.items.orEmpty().filter(::trusted).map { it.asset }
            val local = rank(query, (cached + catalog.search(query)).distinctBy { it.id })
                .mapNotNull(::localAsset)
            Triple(entry, local, entry != null && queryCache.fresh(entry) && cached.all { localAsset(it) != null })
        }
        if (!acceptResponse(requestId)) return@launch
        onResult(local.map(::withBundledThumbnail))
        if (complete) return@launch
        val work = startQuery(normalized, entry) ?: return@launch
        // 只取消订阅者，不取消 sibling 预取。owner scope 销毁仍取消所有任务。
        work.result.await()?.takeIf { acceptResponse(requestId) }
            ?.map(::withBundledThumbnail)?.let(onResult)
    }

    private fun rank(query: String, assets: List<ExpressionAsset>): List<ExpressionAsset> =
        ExpressionCatalog(ExpressionCatalogDocument(catalog.document.version, assets, emptyList(), emptyList()))
            .recommend(query)

    private fun localAsset(asset: ExpressionAsset): ExpressionAsset? {
        val file = runCatching { cache.validFile(asset.version, asset.fileName, asset.sha256) }.getOrNull()
        if (file != null) return asset.copy(resolvedPreviewUrl = "file://${file.absolutePath}")
        val bundled = trustedBundled[asset.id]
        return if (bundled?.distribution != "remote" && matchesBundled(asset)) asset.copy(resolvedPreviewUrl = null)
        else null
    }

    private fun matchesBundled(asset: ExpressionAsset): Boolean = trustedBundled[asset.id]?.let {
        it.version == asset.version && it.fileName == asset.fileName && it.sha256 == asset.sha256
    } == true

    private fun trusted(item: ExpressionQueryCache.Item): Boolean {
        val asset = item.asset
        if (!ExpressionQueryCache.SHA_PATTERN.matches(asset.sha256)) return false
        if (!runCatching { cache.file(asset.version, asset.fileName); true }.getOrDefault(false)) return false
        if (asset.type !in setOf("prebuilt", "synthesis-template") ||
            asset.format !in setOf("gif", "png", "jpg", "jpeg", "webp")) return false
        if (!matchesBundled(asset) && item.sourceType !in setOf("ai-original", "cc0", "public-domain", "licensed")) return false
        return asset.url == null || sameOrigin(asset.url)
    }

    private fun sameOrigin(url: String): Boolean = runCatching {
        val origin = baseUrl.toHttpUrl()
        val target = resolveExpressionRemoteSource(baseUrl, url).toHttpUrl()
        origin.scheme == target.scheme && origin.host == target.host && origin.port == target.port
    }.getOrDefault(false)

    private fun startQuery(query: String, entry: ExpressionQueryCache.Entry?): QueryWork? = synchronized(pendingLock) {
        queries[query]?.let { return@synchronized it }
        val fresh = entry?.takeIf(queryCache::fresh)?.items?.filter(::trusted)
        // 不创建无限排队的协程；忙时已有本地结果仍立即可用，下次输入可重试。
        if (queries.size >= 4) return@synchronized null
        val result = CompletableDeferred<List<ExpressionAsset>?>()
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                withTimeout(30_000) {
                    val items = fresh ?: fetch(query)?.also { queryCache.write(baseUrl, query, it) }
                    if (items == null) return@withTimeout
                    val ranked = rank(query, items.map { it.asset })
                    result.complete(ranked.map { localAsset(it) ?: it })
                    for (asset in ranked) {
                        if (localAsset(asset) == null) download(asset.version, asset.fileName,
                            asset.url ?: "/uploads/expression/${asset.fileName}", asset.sha256)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // 保留已发布/已缓存部分；下次查询恢复缺件。
            } finally { result.complete(null) }
        }
        QueryWork(result).also { work ->
            queries[query] = work
            job.invokeOnCompletion { synchronized(pendingLock) { if (queries[query] === work) queries.remove(query) } }
            job.start()
        }
    }

    private suspend fun fetch(query: String): List<ExpressionQueryCache.Item>? {
        val url = "$baseUrl/api/v1/mobile/expressions/recommend".toHttpUrl().newBuilder()
            .addQueryParameter("q", query).build()
        val request = Request.Builder().url(url).header("X-Device-Id", deviceId).build()
        return networkClient.newCall(request).awaitBody { response ->
            check(response.isSuccessful)
            val body = response.body ?: return@awaitBody null
            check(body.contentLength() <= ExpressionQueryCache.MAX_METADATA_BYTES)
            val data = body.byteStream().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= ExpressionQueryCache.MAX_METADATA_BYTES)
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }
            json.decodeFromString<RecommendationResponse>(data).results.take(20).mapNotNull { wire ->
                runCatching {
                    val asset = json.decodeFromJsonElement(ExpressionAsset.serializer(), wire)
                    val source = (wire["sourceType"] as? JsonPrimitive)?.content
                    val clean = asset.copy(resolvedPreviewUrl = null,
                        thumbnailUrl = asset.thumbnailUrl?.takeIf(::sameOrigin))
                    ExpressionQueryCache.Item(clean, source).takeIf(::trusted)
                }.getOrNull()
            }
        }
    }

    private fun withBundledThumbnail(asset: ExpressionAsset): ExpressionAsset {
        val path = asset.thumbnailFileName
        return if (asset.distribution == "remote" && path != null && bundledThumbnails[asset.id] == path) {
            asset.copy(thumbnailUrl = "file:///android_asset/expression/$path")
        } else {
            asset
        }
    }

    suspend fun download(
        version: String,
        relativePath: String,
        url: String,
        sha256: String,
    ): File? = withContext(Dispatchers.IO) {
        runCatching { cache.validFile(version, relativePath, sha256) }.getOrNull()?.let { return@withContext it }
        if (!sameOrigin(url) || !ExpressionQueryCache.SHA_PATTERN.matches(sha256)) return@withContext null
        if (!runCatching { cache.file(version, relativePath); true }.getOrDefault(false)) return@withContext null
        val work = synchronized(pendingLock) {
            downloads[sha256] ?: if (downloads.size >= 8) null else {
                scope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
                    try {
                        downloadSlots.withPermit {
                            val request = Request.Builder().url(resolveExpressionRemoteSource(baseUrl, url))
                                .header("X-Device-Id", deviceId).build()
                            networkClient.newCall(request).awaitBody { response ->
                                check(response.isSuccessful)
                                val body = response.body ?: return@awaitBody null
                                check(body.contentLength() <= queryCache.maxAssetBytes)
                                queryCache.writeOriginal(sha256, body.byteStream())
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) { null }
                }.also { task ->
                    downloads[sha256] = task
                    task.invokeOnCompletion { synchronized(pendingLock) { if (downloads[sha256] === task) downloads.remove(sha256) } }
                    task.start()
                }
            }
        }
        work?.await()
    }

    @Serializable
    private data class RecommendationResponse(
        val results: List<JsonObject>,
    )
}

private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, error: IOException) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }

        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, unconsumedResponse, _ ->
                unconsumedResponse.close()
            }
        }
    })
}

/** 取消覆盖整个响应体读取，不能只覆盖等待响应头的阶段。 */
private suspend fun <T> Call.awaitBody(read: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, error: IOException) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }
        override fun onResponse(call: Call, response: Response) {
            try {
                val value = response.use(read)
                continuation.resume(value)
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    })
}
