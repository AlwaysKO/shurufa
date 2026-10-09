package com.yuyan.imemodule.data.capture.page

import android.content.Context
import com.yuyan.imemodule.data.capture.adapter.ChatCaptureSettings
import com.yuyan.imemodule.data.collect.*
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** 复用已有唤醒与 Wi-Fi 绑定小包传输；输入/游戏期间暂停，无新增周期器。 */
internal object VideoVisitSync {
    private val mutex = Mutex()
    private val cancellations = AtomicLong()
    private val http = OkHttpClient.Builder().addInterceptor(GameWorkRuntime.interceptor)
        .followRedirects(false).followSslRedirects(false).build()
    fun cancel() { cancellations.incrementAndGet(); http.dispatcher.cancelAll() }
    private fun exists(context: Context) = File(context.noBackupFilesDir, "video_visits.db").isFile
    private fun enabled(platform: String): Boolean = when (platform) {
        "wechat" -> ChatCaptureSettings.rule("com.tencent.mm").enabled
        "douyin" -> ChatCaptureSettings.rule("com.ss.android.ugc.aweme").enabled
        else -> false
    }
    fun hasPending(context: Context): Boolean = CollectionConsent.enabled(context) && exists(context) &&
        runCatching { VideoVisitStore(context).use { it.completed(1).isNotEmpty() } }.getOrDefault(false)
    fun hasDue(context: Context): Boolean = ImageUploadRuntime.canUploadChat(context, ServerConfig.baseUrl) && exists(context) &&
        runCatching { VideoVisitStore(context).use { it.due(System.currentTimeMillis(), limit = 1, platforms = setOf("wechat", "douyin").filter(::enabled).toSet()).isNotEmpty() } }.getOrDefault(false)

    suspend fun flush(context: Context) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val target = ServerConfig.baseUrl
        if (!ImageUploadRuntime.canUploadChat(app, target) || !exists(app) || !mutex.tryLock()) return@withContext
        try {
            val epoch = CollectionConsent.epoch
            val targetEpoch = ServerConfig.onlineEpoch
            val policyRevision = ChatCaptureSettings.revision()
            val cancellation = cancellations.get()
            val device = DataCollector.deviceId(app)
            val job = currentCoroutineContext()[Job]
            fun current() = job?.isActive != false && cancellations.get() == cancellation &&
                CollectionConsent.epoch == epoch && ChatCaptureSettings.revision() == policyRevision &&
                target == ServerConfig.baseUrl && targetEpoch == ServerConfig.onlineEpoch &&
                ImageUploadRuntime.canUploadChat(app, target)
            VideoVisitStore(app).use { store ->
                val result = VideoVisitDelivery(store, System::currentTimeMillis,
                    allowed = { platform -> current() && enabled(platform) },
                    prepare = { ImageUploadRuntime.beginPreparation() },
                    send = { row, payload ->
                        val allowed = { current() && enabled(row.platform) }
                        val pkg = if (row.platform == "wechat") "com.tencent.mm" else "com.ss.android.ugc.aweme"
                        val request = Request.Builder().url("$target/api/v1/mobile/video-visits")
                            .header("X-Device-Id", device)
                            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
                        val call = ImageUploadRuntime.prepareChatCall(app, target, http, request, allowed)
                        if (call == null) null else try {
                            call.execute().use { response ->
                                PageUploadResponse(response.code, if (response.isSuccessful && allowed()) response.body?.let(::readPageReceipt) else null).also { reply ->
                                    if (allowed()) {
                                        val receipt = validateVideoVisitReceipt(reply, row)
                                        PageCaptureDiagnostics.upload(app, pkg,
                                            when (receipt) { false -> "acknowledged"; true -> "discarded"; null -> "failed" },
                                            if (receipt == null) response.code else null)
                                    }
                                }
                            }
                        } catch (failure: Exception) {
                            if (allowed()) PageCaptureDiagnostics.upload(app, pkg, "failed")
                            throw failure
                        } finally { ImageUploadRuntime.finishChatCall(call) }
                    },
                ).runOnce()
                if (result.saved + result.discarded + result.failed > 0)
                    android.util.Log.i("VideoVisitSync", "stored=${result.saved} discarded=${result.discarded} retry=${result.failed}")
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { android.util.Log.w("VideoVisitSync", "upload_or_storage_failed") }
        finally { mutex.unlock() }
    }
}
