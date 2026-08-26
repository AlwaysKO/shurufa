package com.yuyan.imemodule.data.sticker

import android.content.Context
import com.yuyan.imemodule.data.collect.ServerConfig
import com.yuyan.imemodule.data.collect.DataCollector
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 斗图表情包网络层：按关键词搜索服务端表情包、选择后上报使用次数。
 * 失败静默（返回空列表/忽略），任何时候都不影响输入功能。
 */
object StickerSync {

    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    @Volatile
    private var deviceId: String? = null

    fun init(context: Context) {
        deviceId = DataCollector.deviceId(context.applicationContext)
    }

    /** 关键词搜索表情包（阻塞 IO，需在 IO 线程调用） */
    fun search(keyword: String): List<StickerItem> {
        return try {
            val url = ServerConfig.baseUrl + "/api/v1/mobile/stickers?q=" +
                URLEncoder.encode(keyword.trim(), "UTF-8") + "&limit=80"
            val request = Request.Builder()
                .url(url)
                .header("X-Device-Id", deviceId ?: return emptyList())
                .get()
                .build()
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val data = json.decodeFromString(StickerListResponse.serializer(), resp.body?.string() ?: return emptyList())
                data.stickers
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 表情包被选择发送（服务端累加 use_count，用于排序） */
    fun reportUse(id: Long) {
        try {
            val request = Request.Builder()
                .url(ServerConfig.baseUrl + "/api/v1/mobile/stickers/$id/use")
                .header("X-Device-Id", deviceId ?: return)
                .post("".toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            http.newCall(request).execute().close()
        } catch (_: Exception) {
            // 上报失败静默
        }
    }

    /** 下载表情包到 cacheDir/sticker/ 目录，返回文件（失败返回 null） */
    fun download(context: Context, sticker: StickerItem): File? {
        return try {
            val url = ServerConfig.baseUrl + sticker.url
            val request = Request.Builder()
                .url(url)
                .header("X-Device-Id", deviceId ?: return null)
                .get()
                .build()
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val dir = File(context.cacheDir, "sticker").apply { mkdirs() }
                val file = File(dir, "sticker_${sticker.id}.${sticker.format}")
                resp.body?.byteStream()?.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                file
            }
        } catch (_: Exception) {
            null
        }
    }
}

@Serializable
data class StickerItem(
    val id: Long,
    val url: String,
    val format: String = "png",
    val width: Int? = null,
    val height: Int? = null,
)

@Serializable
data class StickerListResponse(
    val total: Int = 0,
    val stickers: List<StickerItem> = emptyList(),
)
