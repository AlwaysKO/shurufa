package com.yuyan.imemodule.data.phrase

import android.content.Context
import com.yuyan.imemodule.data.collect.ServerConfig
import com.yuyan.imemodule.data.collect.DataCollector
import com.yuyan.imemodule.database.DataBaseKT
import com.yuyan.imemodule.database.entry.Phrase
import com.yuyan.imemodule.libs.pinyin4j.PinyinHelper
import com.yuyan.inputmethod.util.LX17PinYinUtils
import com.yuyan.inputmethod.util.T9PinYinUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 常用语云同步器：
 * - 启动后周期性拉取云端常用语全量，与本地 phrase 表按 cloudId 合并（新增/改词/删除收敛）；
 * - 本地手动新增的常用语上报服务端（按 content 幂等）；
 * - 常用语被使用时上报计数。
 * 同步失败静默（下一周期自动重试），任何时候都不影响输入功能。
 */
object PhraseSync {

    private const val SYNC_INTERVAL_MS = 30 * 60 * 1000L

    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var deviceId: String? = null

    fun init(context: Context) {
        val app = context.applicationContext
        deviceId = DataCollector.deviceId(app)
        scope.launch {
            delay(3_000) // 等输入法初始化完成
            sync(app)
            while (true) {
                delay(SYNC_INTERVAL_MS)
                sync(app)
            }
        }
    }

    /** 本地新增常用语上报（服务端按 content 幂等 upsert） */
    fun upload(content: String) {
        scope.launch {
            try {
                val body = json.encodeToString(PhraseBody.serializer(), PhraseBody(content = content))
                post("/api/v1/mobile/phrases", body)
            } catch (_: Exception) {
                // 上报失败静默，下次同步仍会上报（本地 cloudId=0）
            }
        }
    }

    /** 常用语被使用（服务端累加 use_count） */
    fun reportUse(content: String) {
        scope.launch {
            try {
                val body = json.encodeToString(PhraseBody.serializer(), PhraseBody(content = content))
                post("/api/v1/mobile/phrases/use", body)
            } catch (_: Exception) {
                // 上报失败静默，不影响输入
            }
        }
    }

    // ---------- 内部 ----------

    private fun sync(context: Context) {
        try {
            val request = Request.Builder()
                .url(ServerConfig.baseUrl + "/api/v1/mobile/phrases")
                .header("Content-Type", "application/json")
                .header("X-Device-Id", deviceId ?: return)
                .get()
                .build()
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return
                val data = json.decodeFromString(PhraseSyncResponse.serializer(), resp.body?.string() ?: return)
                merge(data.phrases)
            }
        } catch (_: Exception) {
            // 同步失败静默，下一周期重试
        }
    }

    /** 云端全量与本地合并：新增插入、改词更新、云端已删的本地收敛删除 */
    private fun merge(cloud: List<CloudPhrase>) {
        val dao = DataBaseKT.instance.phraseDao()
        val local = dao.getAll()
        for (c in cloud) {
            val localByCloud = local.firstOrNull { it.cloudId == c.id }
            if (localByCloud != null) {
                if (localByCloud.content != c.content) {
                    val indexes = buildIndexes(c.content)
                    dao.updateCloudContent(c.id, c.content, indexes.first, indexes.second, indexes.third)
                }
            } else {
                val localByContent = local.firstOrNull { it.content == c.content && it.cloudId == 0L }
                if (localByContent != null) {
                    dao.updateCloudId(c.content, c.id) // 本地已有同内容 → 标记为云端管理
                } else {
                    val indexes = buildIndexes(c.content)
                    dao.insert(Phrase(content = c.content, t9 = indexes.first, qwerty = indexes.second, lx17 = indexes.third, cloudId = c.id))
                }
            }
        }
        val cloudIds = cloud.map { it.id }.toSet()
        dao.getCloudPhrases().forEach { p ->
            if (p.cloudId !in cloudIds) dao.deleteByCloudId(p.cloudId) // 云端已删除 → 本地移除
        }
    }

    /** 与 EditPhrasesView 一致：拼音首字母（小写）→ qwerty / t9 / lx17 索引 */
    private fun buildIndexes(content: String): Triple<String, String, String> {
        val qwerty = PinyinHelper.getPinYinHeadChar(content)
        val t9 = qwerty.map { T9PinYinUtils.pinyin2T9Key(it) }.joinToString("")
        val lx17 = qwerty.map { LX17PinYinUtils.pinyin2Lx17Key(it) }.joinToString("")
        return Triple(t9, qwerty, lx17)
    }

    private fun post(path: String, bodyJson: String): okhttp3.Response {
        val request = Request.Builder()
            .url(ServerConfig.baseUrl + path)
            .header("Content-Type", "application/json")
            .header("X-Device-Id", deviceId ?: error("PhraseSync is not initialized"))
            .post(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return http.newCall(request).execute()
    }
}

// ---------- 协议 DTO（与服务端 /api/v1/mobile/phrases* 对应） ----------

@Serializable
data class CloudPhrase(
    val id: Long,
    val content: String,
)

@Serializable
data class PhraseSyncResponse(
    val total: Int = 0,
    val phrases: List<CloudPhrase> = emptyList(),
)

@Serializable
data class PhraseBody(
    val content: String,
)
