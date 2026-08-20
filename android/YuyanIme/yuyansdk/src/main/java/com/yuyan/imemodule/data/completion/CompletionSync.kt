package com.yuyan.imemodule.data.completion

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.yuyan.imemodule.data.collect.ServerConfig
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

    private const val KEY_VERSION = "completion_sync_version"
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
    private var prefs: SharedPreferences? = null
    @Volatile
    private var syncedVersion = 0

    fun init(context: Context) {
        val app = context.applicationContext
        prefs = PreferenceManager.getDefaultSharedPreferences(app)
        syncedVersion = prefs?.getInt(KEY_VERSION, 0) ?: 0
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
            val hits = synchronized(lock) { cache[prefix] }
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
        scope.launch {
            try {
                val body = json.encodeToString(
                    FeedbackBody.serializer(),
                    FeedbackBody(completion = candidate.completion, prefix = candidate.prefix, accepted = true),
                )
                post("/api/v1/mobile/completions/feedback", body)
            } catch (_: Exception) {
                // 上报失败静默，不影响输入
            }
        }
    }

    // ---------- 内部 ----------

    private fun sync(context: Context) {
        try {
            val request = Request.Builder()
                .url(ServerConfig.baseUrl + "/api/v1/mobile/completions?since=$syncedVersion")
                .header("Content-Type", "application/json")
                .get()
                .build()
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return
                val data = json.decodeFromString(CompletionSyncResponse.serializer(), resp.body?.string() ?: return)
                if (data.version <= syncedVersion) return
                synchronized(lock) {
                    data.candidates.forEach { c ->
                        val list = cache.getOrPut(c.prefix) { mutableListOf() }
                        list.removeAll { it.completion == c.completion }
                        list.add(c)
                    }
                    syncedVersion = data.version
                }
                prefs?.edit()?.putInt(KEY_VERSION, syncedVersion)?.apply()
            }
        } catch (_: Exception) {
            // 同步失败静默，下一周期重试
        }
    }

    private fun post(path: String, bodyJson: String): okhttp3.Response {
        val request = Request.Builder()
            .url(ServerConfig.baseUrl + path)
            .header("Content-Type", "application/json")
            .post(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return http.newCall(request).execute()
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
