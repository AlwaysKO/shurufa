package com.yuyan.imemodule.data.completion

import android.content.Context
import com.yuyan.imemodule.data.collect.CollectionConsent
import com.yuyan.imemodule.data.collect.ServerConfig
import com.yuyan.imemodule.data.collect.DataCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 服务端智能补全同步器：增量拉取补全候选（?since=version）缓存到内存，
 * 联想流程按文本后缀查询；候选被接受时上报 feedback 供服务端统计。
 * 同步失败静默（下一周期自动重试），任何时候都不影响输入功能。
 */
object CompletionSync {

    private const val SYNC_INTERVAL_MS = 30 * 60 * 1000L
    private const val CANDIDATE_COMMENT = "☁️" // 候选栏标记：服务端补全候选
    private const val MAX_QUERY_TAIL = 6 // 服务端最多生成 6 字前缀，只匹配文本末尾 6 字

    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val lock = Any()
    private val cache = HashMap<String, MutableList<CompletionCandidate>>()

    @Volatile
    private var deviceId: String? = null
    @Volatile
    private var syncedVersion = 0
    private var cacheEndpoint = ""
    private var diskCache: CompletionCache? = null
    private var initialized = false

    @Synchronized fun init(context: Context) {
        if (initialized) return
        initialized = true
        val app = context.applicationContext
        deviceId = DataCollector.deviceId(app)
        // 历史 SharedPreferences 的单独版本号不再使用：没有快照就从 0 同步。
        restoreCache(app)
        scope.launch {
            sync(app)
            while (true) {
                delay(SYNC_INTERVAL_MS)
                sync(app)
            }
        }
    }

    /** 候选栏标记：服务端补全候选（供 InputView 识别与上报） */
    val candidateComment: String
        get() = CANDIDATE_COMMENT

    /**
     * 查询与文本后缀匹配的补全候选（最长后缀优先，取 use_count 前 3）。
     * 纯内存查询，可安全在输入线程调用。
     */
    fun query(text: String): List<CompletionCandidate> {
        val tail = text.takeLast(MAX_QUERY_TAIL)
        for (i in tail.length downTo 1) {
            val prefix = tail.takeLast(i)
            val hits = synchronized(lock) { cache[prefix]?.toList() }
            if (!hits.isNullOrEmpty()) {
                return hits.sortedByDescending { it.useCount }.take(3)
            }
        }
        return emptyList()
    }

    /** 按完整短语反查候选（选择上屏后用于上报，缓存未命中则忽略） */
    fun find(completion: String): CompletionCandidate? {
        synchronized(lock) {
            for (list in cache.values) {
                list.firstOrNull { it.completion == completion }?.let { return it }
            }
        }
        return null
    }

    /** 上报候选被接受（服务端累加 accept_count） */
    fun reportAccepted(context: Context, candidate: CompletionCandidate) {
        DataCollector.enqueueReport(context, "completion_feedback", json.encodeToString(
            FeedbackBody.serializer(), FeedbackBody(candidate.completion, candidate.prefix, true)))
    }

    // ---------- 内部 ----------

    private fun restoreCache(context: Context) {
        cacheEndpoint = ServerConfig.baseUrl
        diskCache = CompletionCache(context.filesDir, deviceId ?: return, cacheEndpoint)
        val restored = diskCache!!.load()
        synchronized(lock) {
            cache.clear()
            restored.candidates.forEach { c -> cache.getOrPut(c.prefix) { mutableListOf() }.add(c) }
            syncedVersion = restored.version
        }
    }

    private fun sync(context: Context) {
        if (!CollectionConsent.enabled(context)) return
        try {
            if (cacheEndpoint != ServerConfig.baseUrl) restoreCache(context)
            val request = Request.Builder()
                .url(cacheEndpoint + "/api/v1/mobile/completions?since=$syncedVersion")
                .header("Content-Type", "application/json")
                .header("X-Device-Id", deviceId ?: return)
                .get()
                .build()
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return
                val data = json.decodeFromString(CompletionSyncResponse.serializer(), resp.body?.string() ?: return)
                if (data.version < syncedVersion) {
                    diskCache?.save(0, emptyList())
                    synchronized(lock) { cache.clear(); syncedVersion = 0 }
                    return
                }
                // 旧接口同批词条共享 version，不能用全局 version 跳过未收到的分页。
                // 保留当前可用快照；完整分页需要后端原子快照协议，非本轮范围。
                if (data.hasMore || data.version <= syncedVersion) return
                synchronized(lock) {
                    data.candidates.forEach { c ->
                        val list = cache.getOrPut(c.prefix) { mutableListOf() }
                        list.removeAll { it.completion == c.completion }
                        list.add(c)
                    }
                    diskCache?.save(data.version, cache.values.flatten())
                    syncedVersion = data.version
                }
            }
        } catch (_: Exception) {
            // 同步失败静默，下一周期重试；输入和本地学习不依赖此接口。
        }
    }


}

// ---------- 协议 DTO（与服务端 /api/v1/mobile/completions* 对应，snake_case） ----------

@Serializable
data class CompletionCandidate(
    val id: Long,
    val prefix: String,
    val completion: String,
    @SerialName("use_count") val useCount: Long = 0,
    val version: Int = 0,
)

@Serializable
data class CompletionSyncResponse(
    val version: Int = 0,
    @SerialName("has_more") val hasMore: Boolean = false,
    val candidates: List<CompletionCandidate> = emptyList(),
)

@Serializable
data class FeedbackBody(
    val completion: String,
    val prefix: String,
    val accepted: Boolean,
)
