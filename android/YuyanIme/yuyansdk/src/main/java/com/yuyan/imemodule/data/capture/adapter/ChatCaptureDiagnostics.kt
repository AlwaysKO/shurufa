package com.yuyan.imemodule.data.capture.adapter

import android.content.Context
import com.yuyan.imemodule.data.collect.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** release 同样启用。事件只更新八槽内存；已有 Collector 在空闲时落盘与有界补传。 */
object ChatCaptureDiagnostics {
    private val buffer = ChatDiagnosticsBuffer()
    private val mutex = Mutex()
    private var loaded = false
    @Volatile private var deviceId: String? = null
    fun bindDeviceId(value: String) { deviceId = value }
    private val http = OkHttpClient.Builder().addInterceptor(GameWorkRuntime.interceptor)
        .connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).callTimeout(5, TimeUnit.SECONDS).build()
    private val prefsName = "chat_capture_diagnostics_v1"
    fun cancel() { http.dispatcher.cancelAll() }
    fun record(context: Context, platform: String, stage: String, status: String, errorCode: Int? = null) {
        if (!CollectionConsent.enabled(context) || platform !in setOf("wechat", "douyin")) return
        val id = deviceId ?: return
        val pkg = if (platform == "wechat") "com.tencent.mm" else "com.ss.android.ugc.aweme"
        val version = ChatCaptureSettings.version(pkg) ?: CaptureAppVersion(0, "未知")
        buffer.record(CaptureDiagnosticSnapshot(id, platform, version.code, version.name,
            ChatCaptureSettings.revision(), stage, status, errorCode, System.currentTimeMillis()))
    }
    private fun load(context: Context) {
        if (loaded) return
        loaded = true
        val raw = context.getSharedPreferences(prefsName, 0).getString("latest", null) ?: return
        if (raw.toByteArray(Charsets.UTF_8).size > 32768) return
        runCatching { Json.decodeFromString<List<CaptureDiagnosticSnapshot>>(raw) }.getOrNull()
            ?.takeIf { it.size <= 8 }?.forEach(buffer::record)
    }
    suspend fun latest(context: Context): List<CaptureDiagnosticSnapshot> = withContext(Dispatchers.IO) {
        mutex.withLock { load(context.applicationContext); buffer.latest() }
    }
    suspend fun flush(context: Context) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        mutex.withLock {
            if (!CollectionConsent.enabled(app) || !ImageUploadRuntime.isBackgroundWorkAllowed()) return@withLock
            load(app)
            app.getSharedPreferences(prefsName, 0).edit().putString("latest", Json.encodeToString(buffer.latest())).apply()
            val source = ServerConfig.baseUrl
            val allowed = { CollectionConsent.enabled(app) && ImageUploadRuntime.isBackgroundWorkAllowed() && ServerConfig.baseUrl == source }
            val reporter = ChatDiagnosticsReporter(buffer,
                prepare = { request -> ImageUploadRuntime.prepareChatCall(app, source, http, request, allowed, preserveCallTimeout = true) },
                finish = ImageUploadRuntime::finishChatCall)
            reporter.flush(source, allowed, android.os.SystemClock::elapsedRealtime)
        }
    }
}
