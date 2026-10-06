package com.yuyan.imemodule.data.capture.adapter

import android.content.Context
import android.os.Build
import com.yuyan.imemodule.data.collect.CollectionConsent
import com.yuyan.imemodule.data.collect.BackgroundRefreshRuntime
import com.yuyan.imemodule.data.collect.DataCollector
import com.yuyan.imemodule.data.collect.GameWorkRuntime
import com.yuyan.imemodule.data.collect.ImageUploadRuntime
import com.yuyan.imemodule.data.collect.ServerConfig
import com.yuyan.imemodule.data.capture.sha256
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** 后台拉取加入共享刷新批次；事件解析只读内存，绝不查询 PM 或联网。 */
object ChatCaptureSettings {
    @Volatile private var runtime: ChatCaptureRefreshController? = null
    private val http = OkHttpClient.Builder().addInterceptor(GameWorkRuntime.interceptor)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS).build()
    fun rule(packageName: String): ChatCaptureRule = runtime?.rule(ServerConfig.baseUrl, packageName)
        ?: requireNotNull(ChatCapturePolicy.builtIn().rule(packageName, 0))
    fun revision(): Long = runtime?.policy(ServerConfig.baseUrl)?.revision ?: 0
    fun version(packageName: String): CaptureAppVersion? = runtime?.version(packageName)
    fun cancel() { http.dispatcher.cancelAll() }

    suspend fun refresh(context: Context, userInitiated: Boolean = false) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        if (!CollectionConsent.enabled(app) || !ImageUploadRuntime.isBackgroundWorkAllowed()) return@withContext
        controller(app).restore(ServerConfig.baseUrl)
        BackgroundRefreshRuntime.request(app, userInitiated)
    }
    /** 由前台已有采集任务触发，仅更新本地宿主版本/缓存，不唤醒网络批次。 */
    suspend fun refreshLocal(context: Context) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        if (!CollectionConsent.enabled(app) || !ImageUploadRuntime.isBackgroundWorkAllowed()) return@withContext
        controller(app).restore(ServerConfig.baseUrl)
    }
    internal suspend fun refreshInBatch(context: Context) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        if (!CollectionConsent.enabled(app) || !ImageUploadRuntime.isBackgroundWorkAllowed()) return@withContext
        controller(app).refresh(ServerConfig.baseUrl, userInitiated = true)
    }
    private fun controller(app: Context) = synchronized(this) {
        runtime ?: create(app).also { runtime = it }
    }
    private fun create(app: Context): ChatCaptureRefreshController {
        val prefs = app.getSharedPreferences("chat_capture_policy_v1", Context.MODE_PRIVATE)
        fun key(source: String) = "source_" + sha256(source.toByteArray(Charsets.UTF_8))
        return ChatCaptureRefreshController(
            allowed = { CollectionConsent.enabled(app) && ImageUploadRuntime.isBackgroundWorkAllowed() },
            loadVersion = { pkg ->
                @Suppress("DEPRECATION")
                val info = app.packageManager.getPackageInfo(pkg, 0)
                val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
                CaptureAppVersion(code.coerceIn(0, 9007199254740991L),
                    info.versionName.orEmpty().filterNot { it.code < 32 || it.code == 127 }.take(80).ifBlank { "未知" })
            },
            readCache = { prefs.getString(key(it), null) },
            writeCache = { source, raw ->
                // 每份缓存已严格验证；最多四份，不积累历史后台配置。
                val k = key(source)
                val editor = prefs.edit().putString(k, raw)
                prefs.all.keys.filter { it.startsWith("source_") && it != k }.sorted().drop(3).forEach(editor::remove)
                editor.apply()
            },
            fetch = fetch@ { source ->
                val request = Request.Builder().url("$source/api/v1/mobile/chat-capture-config")
                    .header("X-Device-Id", DataCollector.deviceId(app)).get().build()
                val allowed = { CollectionConsent.enabled(app) && ImageUploadRuntime.isBackgroundWorkAllowed() && ImageUploadRuntime.hasValidatedNetwork(app) && ServerConfig.baseUrl == source }
                val call = ImageUploadRuntime.prepareBackgroundCall(http, request, allowed) ?: return@fetch null
                try { call.execute().use { response ->
                    val body = response.body
                    if (!allowed() || !response.isSuccessful || body == null || body.contentLength() > ChatCapturePolicy.MAX_BYTES) null
                    else {
                        // 未知长度/分块响应也有硬上限，不先读完整 HTML/大 JSON。
                        val bytes = body.byteStream().use { stream ->
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(4096)
                            while (output.size() <= ChatCapturePolicy.MAX_BYTES) {
                                val count = stream.read(buffer, 0, minOf(buffer.size, ChatCapturePolicy.MAX_BYTES + 1 - output.size()))
                                if (count < 0) break
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        }
                        if (bytes.size > ChatCapturePolicy.MAX_BYTES || !allowed()) null else
                            Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                    }
                } } finally { ImageUploadRuntime.finishChatCall(call) }
            },
        )
    }
}
