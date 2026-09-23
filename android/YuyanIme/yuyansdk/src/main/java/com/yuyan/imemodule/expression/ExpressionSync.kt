package com.yuyan.imemodule.expression

import com.yuyan.imemodule.expression.model.ExpressionAsset
import com.yuyan.imemodule.expression.model.ExpressionCatalogDocument
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
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
    catalogDirectory: File = File(cache.queryRoot, "catalogs"),
) {
    @Volatile
    private var catalog = initialCatalog
    private val json = Json { ignoreUnknownKeys = true }
    // 生成链保证初始 APK 目录的缩略图全部内置；之后新增的远端目录不能冒充本地资源。
    private val bundledThumbnails = initialCatalog.document.templates
        .mapNotNull { asset -> asset.thumbnailFileName?.let { asset.id to it } }.toMap()

    private val catalogStore = ExpressionCatalogStore(catalogDirectory, baseUrl, deviceId, initialCatalog.document.version)
    internal val backgroundSyncKey: String get() = catalogStore.key
    private val refreshMutex = catalogStore.refreshMutex
    private val keyboardLock = Any()
    private var keyboardSession: Job? = null

    private data class VerifiedPreview(val file: File, val bytes: Long, val modified: Long)
    private val verifiedPreviews = java.util.concurrent.ConcurrentHashMap<String, VerifiedPreview>()

    /** UI只看已验证结果/内置SHA元数据，绝不在render时扫描全部GIF内容。 */
    fun currentCatalog(): ExpressionCatalog {
        val snapshot = catalog
        if (!snapshot.document.complete) return snapshot
        return ExpressionCatalog(snapshot.document.copy(templates = snapshot.document.templates.map { asset ->
            bundledAsset(asset) ?: verifiedPreviews[asset.sha256]?.takeIf {
                it.file.isFile && it.file.length() == it.bytes && it.file.lastModified() == it.modified
            }?.let { asset.copy(resolvedPreviewUrl = "file://${it.file.absolutePath}", localPreviewOnly = true) }
                ?: asset.copy(localPreviewOnly = true, distribution = "remote")
        }))
    }

    /** onWindowShown重复通知/开关AI面板不产生额外版本请求；真正隐藏后才开始下一次检查。 */
    fun onKeyboardOpened(checkRemoteVersion: Boolean = true, onChanged: () -> Unit = {}): Job = synchronized(keyboardLock) {
        keyboardSession ?: scope.launch(start = CoroutineStart.LAZY) {
            // 离线重启也先显示已落盘的个人底图，不等待版本接口超时；此阶段绝不下载。
            withContext(Dispatchers.IO) {
                if (refreshMutex.tryLock()) {
                    try { reloadPersistedCatalog() } finally { refreshMutex.unlock() }
                }
                catalog.document.templates.filter { it.type == "synthesis-template" && !matchesBundled(it) }
                    .forEach(::localAsset)
            }
            onChanged()
            if (checkRemoteVersion) {
                refreshMutex.withLock {
                    reloadPersistedCatalog()
                    checkVersion()
                }
                onChanged()
            }
            // 合成池无关键词门禁，提前补齐新底图；不预取全推荐库，推荐原件按匹配懒取。
            for (asset in catalog.document.templates.filter { it.type == "synthesis-template" && !matchesBundled(it) }) {
                withContext(Dispatchers.IO) {
                    if (stillCurrent(asset) && localAsset(asset) == null) {
                        download(asset.version, asset.fileName,
                            asset.url ?: "/uploads/expression/${asset.fileName}", asset.sha256)
                        localAsset(asset)
                    }
                }
                onChanged()
            }
        }.also { keyboardSession = it; it.start() }
    }

    fun onKeyboardClosed() = synchronized(keyboardLock) {
        // 保留已开始的元数据请求和字节预取，防止切换窗口浪费已经下载的流量。
        keyboardSession = null
    }

    /** 半小时后台探测只读取版本，不下载目录或原件。 */
    suspend fun remoteVersion(): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("${baseUrl.trimEnd('/')}/api/v1/mobile/expressions/versions")
                .header("X-Device-Id", deviceId).build()
            networkClient.newCall(request).awaitBody { response ->
                check(response.isSuccessful)
                json.decodeFromString<VersionResponse>(readMetadata(response, 4096)).version
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }

    private suspend fun checkVersion(): Boolean {
        val version = remoteVersion() ?: return false
        return if (version != catalog.document.version) refreshCatalogLocked()?.document?.version == version else true
    }

    suspend fun backgroundSyncNeeded(version: String): Boolean = withContext(Dispatchers.IO) {
        refreshMutex.withLock { reloadPersistedCatalog() }
        if (catalog.document.version != version || !catalog.document.complete) return@withContext true
        queryCache.hasRoomForBackgroundOriginal() && backgroundCandidates().any { localAsset(it) == null }
    }

    private fun backgroundCandidates(): List<ExpressionAsset> {
        val snapshot = catalog.document
        val ids = snapshot.recommendationGroups?.filter { it.aliases.isNotEmpty() }
            ?.flatMap { it.assetIds }?.toSet()
        return snapshot.templates.filter {
            it.type == "prebuilt" && (ids?.contains(it.id) ?: it.keywords.isNotEmpty())
        }.distinctBy { it.sha256 }
    }

    /** Wi-Fi 任务复用探测版本；上轮缺件即使版本未变也继续补齐，单轮有界。 */
    suspend fun syncInBackground(maxDownloads: Int = 24, expectedVersion: String? = null): Boolean = withContext(Dispatchers.IO) {
        require(maxDownloads > 0)
        val updated = refreshMutex.withLock {
            reloadPersistedCatalog()
            when {
                expectedVersion == null -> checkVersion()
                expectedVersion == catalog.document.version && catalog.document.complete -> true
                else -> {
                    refreshCatalogLocked() ?: throw IOException("background catalog update failed")
                    true
                }
            }
        }
        if (!updated || !catalog.document.complete) return@withContext false
        val candidates = backgroundCandidates()
        var attempts = 0
        var complete = true
        for (asset in candidates) {
            if (!stillCurrent(asset) || localAsset(asset) != null) continue
            if (attempts++ >= maxDownloads) return@withContext false
            if (!queryCache.hasRoomForBackgroundOriginal()) return@withContext false
            if (download(asset.version, asset.fileName,
                    asset.url ?: "/uploads/expression/${asset.fileName}", asset.sha256,
                    allowCacheEviction = false) == null) complete = false
        }
        complete
    }

    private fun readMetadata(response: Response, limit: Int): String {
        val body = requireNotNull(response.body)
        check(body.contentLength() <= limit)
        return body.byteStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(output.size() + count <= limit)
                output.write(buffer, 0, count)
            }
            output.toString("UTF-8")
        }
    }


    suspend fun refreshCatalog(): ExpressionCatalog = withContext(Dispatchers.IO) {
        refreshMutex.withLock {
            reloadPersistedCatalog()
            refreshCatalogLocked() ?: catalog
        }
    }

    private suspend fun refreshCatalogLocked(): ExpressionCatalog? = withContext(Dispatchers.IO) {
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
            val remote = networkClient.newCall(request).awaitBody { response ->
                if (response.code == 304) return@awaitBody null
                check(response.isSuccessful) { "catalog request failed: ${response.code}" }
                json.decodeFromString<ExpressionCatalogDocument>(
                    readMetadata(response, ExpressionCatalogStore.MAX_BYTES),
                )
            } ?: return@withContext catalog
            // 发布留在持锁协程内；被取消的 OkHttp 回调不能继续覆盖新目录。
            check(!catalog.document.complete || remote.complete) { "incomplete catalog cannot replace an authoritative snapshot" }
            val accepted = if (remote.complete) sanitizeSnapshot(remote) else remote
            if (accepted.complete) catalogStore.write(accepted)
            catalog = catalog.merge(accepted)
            catalog
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private val queryCache = ExpressionQueryCache(cache, maxAssetBytes = 10L * 1024 * 1024)
    private val trustedInitialAssets = initialCatalog.document.templates.associateBy { it.id }
    private val trustedBundled = initialCatalog.document.templates.filter { it.distribution != "remote" }.associateBy { it.sha256 }
    private val networkClient = client.newBuilder().callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    private val pendingLock = Any()
    private val queries = mutableMapOf<String, QueryWork>()
    private val downloads = mutableMapOf<String, Deferred<File?>>()
    private val downloadSlots = Semaphore(2)
    private class QueryWork(val result: CompletableDeferred<List<ExpressionAsset>?>)

    init {
        reloadPersistedCatalog()
    }

    private fun reloadPersistedCatalog() {
        catalogStore.read()?.let { saved ->
            if (saved.version != catalog.document.version) {
                runCatching { sanitizeSnapshot(saved) }.getOrNull()?.let { catalog = ExpressionCatalog(it) }
            }
        }
    }

    private fun sanitizeSnapshot(document: ExpressionCatalogDocument): ExpressionCatalogDocument {
        require(document.version.matches(Regex("[A-Za-z0-9._-]+")))
        require(document.templates.size <= 10000)
        return document.copy(templates = document.templates.filter { asset ->
            trusted(ExpressionQueryCache.Item(asset, asset.sourceType))
        }.map { it.copy(resolvedPreviewUrl = null, localPreviewOnly = false,
            thumbnailUrl = it.thumbnailUrl?.takeIf(::sameOrigin)) })
    }


    fun search(
        query: String,
        requestId: Long,
        acceptResponse: (Long) -> Boolean,
        automatic: Boolean = false,
        onResult: (List<ExpressionAsset>) -> Unit,
    ): Job = scope.launch {
        val normalized = if (automatic) ExpressionQueryMatching.normalizeAutomatic(query) else ExpressionQueryMatching.normalize(query)
        if (normalized.isEmpty() || (!automatic && normalized.length > 100)) return@launch
        val snapshot = catalog
        if (automatic || snapshot.document.complete || snapshot.document.recommendationGroups != null) {
            val candidates = if (automatic) snapshot.recommend(query) else snapshot.search(query)
            val local = withContext(Dispatchers.IO) { candidates.mapNotNull(::localAsset) }
            if (catalog === snapshot && acceptResponse(requestId)) onResult(local.filter(::stillCurrent))
            // 独立于订阅者，快速输入取消旧搜索时仍完成已启动的SHA原件预取。
            val prefetch = scope.async(Dispatchers.IO) {
                for (asset in candidates) if (stillCurrent(asset) && localAsset(asset) == null) {
                    download(asset.version, asset.fileName,
                        asset.url ?: "/uploads/expression/${asset.fileName}", asset.sha256)
                }
                candidates.mapNotNull(::localAsset)
            }
            val loaded = prefetch.await()
            if (catalog === snapshot && acceptResponse(requestId)) onResult(loaded.filter(::stillCurrent))
            return@launch
        }
        val (entry, local, complete) = withContext(Dispatchers.IO) {
            val entry = queryCache.read(baseUrl, normalized)
            val cached = entry?.items.orEmpty().filter(::trusted).map { it.asset }
            val local = rank(query, (cached + catalog.search(query)).distinctBy { it.id })
                .mapNotNull(::localAsset)
            Triple(entry, local, entry != null && queryCache.fresh(entry) && cached.all { localAsset(it) != null })
        }
        if (catalog !== snapshot || !acceptResponse(requestId)) return@launch
        onResult(local.map(::withBundledThumbnail))
        if (complete) return@launch
        val work = startQuery(normalized, entry) ?: return@launch
        // 只取消订阅者，不取消 sibling 预取。owner scope 销毁仍取消所有任务。
        work.result.await()?.takeIf { catalog === snapshot && acceptResponse(requestId) }
            ?.map(::withBundledThumbnail)?.let(onResult)
    }

    private fun rank(query: String, assets: List<ExpressionAsset>): List<ExpressionAsset> =
        ExpressionCatalog(ExpressionCatalogDocument(catalog.document.version, assets, emptyList(), emptyList(), catalog.document.retiredTemplateIds))
            .search(query)

    private fun stillCurrent(asset: ExpressionAsset): Boolean = catalog.document.templates.any {
        it.id == asset.id && it.sha256 == asset.sha256 && it.id !in catalog.document.retiredTemplateIds
    }

    private fun bundledAsset(asset: ExpressionAsset): ExpressionAsset? = trustedBundled[asset.sha256]?.let { bundled ->
        // ID/远端路径可变，真正相同的SHA复用APK物理路径，预览与发送都可直接打开。
        asset.copy(fileName = bundled.fileName, thumbnailFileName = bundled.thumbnailFileName,
            distribution = "bundled", resolvedPreviewUrl = null, url = null, thumbnailUrl = null,
            localPreviewOnly = catalog.document.complete)
    }

    /** 仅在IO搜索/预取中读取SHA，不在UI render中调用。 */
    private fun localAsset(asset: ExpressionAsset): ExpressionAsset? {
        bundledAsset(asset)?.let { return it }
        val file = runCatching { cache.validFile(asset.version, asset.fileName, asset.sha256) }.getOrNull()
        if (file != null) {
            verifiedPreviews[asset.sha256] = VerifiedPreview(file, file.length(), file.lastModified())
            return asset.copy(resolvedPreviewUrl = "file://${file.absolutePath}", localPreviewOnly = catalog.document.complete)
        }
        verifiedPreviews.remove(asset.sha256)
        return null
    }

    private fun matchesBundled(asset: ExpressionAsset): Boolean = asset.sha256 in trustedBundled

    private fun trusted(item: ExpressionQueryCache.Item): Boolean {
        val asset = item.asset
        if (asset.id in catalog.document.retiredTemplateIds) return false
        if (!ExpressionQueryCache.SHA_PATTERN.matches(asset.sha256)) return false
        if (!runCatching { cache.file(asset.version, asset.fileName); true }.getOrDefault(false)) return false
        if (asset.type !in setOf("prebuilt", "synthesis-template") ||
            asset.format !in setOf("gif", "png", "jpg", "jpeg", "webp")) return false
        val matchesInitial = trustedInitialAssets[asset.id]?.let {
            it.fileName == asset.fileName && it.sha256 == asset.sha256
        } == true
        if (!matchesBundled(asset) && !matchesInitial && item.sourceType !in setOf("ai-original", "cc0", "public-domain", "licensed", "owner-upload")) return false
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
        allowCacheEviction: Boolean = true,
    ): File? = withContext(Dispatchers.IO) {
        runCatching { cache.validFile(version, relativePath, sha256) }.getOrNull()?.let { return@withContext it }
        if (!sameOrigin(url) || !ExpressionQueryCache.SHA_PATTERN.matches(sha256)) return@withContext null
        if (!runCatching { cache.file(version, relativePath); true }.getOrDefault(false)) return@withContext null
        val work = synchronized(pendingLock) {
            downloads[sha256] ?: if (downloads.size >= 8) null else {
                scope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
                    try {
                        downloadSlots.withPermit {
                            // 取得限流槽时另一下载可能刚落盘，真正HTTP前再查一次，关闭并发空隙。
                            cache.validFile(version, relativePath, sha256)?.let { return@withPermit it }
                            val request = Request.Builder().url(resolveExpressionRemoteSource(baseUrl, url))
                                .header("X-Device-Id", deviceId).build()
                            networkClient.newCall(request).awaitBody { response ->
                                check(response.isSuccessful)
                                val body = response.body ?: return@awaitBody null
                                val limit = if (catalog.document.complete && catalog.document.templates.any {
                                    it.sha256 == sha256 && it.type == "synthesis-template"
                                }) 250L * 1024 else queryCache.maxAssetBytes
                                check(body.contentLength() <= limit)
                                queryCache.writeOriginal(sha256, body.byteStream(), limit, allowCacheEviction)
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
    private data class VersionResponse(val version: String)

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
