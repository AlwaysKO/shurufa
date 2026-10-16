package com.yuyan.imemodule.data.capture.page

import android.content.Context
import android.os.SystemClock
import java.util.concurrent.TimeUnit
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

/** 视频结束元数据两网五分钟合批；图片仍走独立 Wi-Fi 队列，输入/游戏期间暂停。 */
internal object VideoVisitSync {
    private val cadence = CollectorSyncCadence(300_000L,300_000L,SystemClock::elapsedRealtime)
    /** 在 IO 线程查询：空队列、关闭平台、离线或输入/游戏暂停不创建短轮询。 */
    fun nextUploadDelay(context: Context, includePaused: Boolean = false): Long? {
        if (!CollectionConsent.enabled(context) || !ImageUploadRuntime.hasValidatedNetwork(context) || !exists(context)) return null
        val paused = !ImageUploadRuntime.isBackgroundWorkAllowed()
        if (paused && !includePaused) return null
        val pending = runCatching { VideoVisitStore(context).use {
            it.nextDeliveryDelay(System.currentTimeMillis(), enabledPlatforms(), pendingImageIds(context))
        } }.getOrNull() ?: return null
        return maxOf(pending, cadence.wakeDelay(ImageUploadRuntime.hasValidatedWifi(context)), if (paused) 300_000L else 0L)
    }
    /** 已有可交付元数据离线也预约；仍尊重平台、图片依赖与重试时间。 */
    fun pendingRecoveryDelay(context: Context): Long? {
        if (!CollectionConsent.enabled(context) || !exists(context)) return null
        val pending = VideoVisitStore(context).use {
            it.nextDeliveryDelay(System.currentTimeMillis(), enabledPlatforms(), pendingImageIds(context))
        } ?: return null
        return maxOf(300_000L, pending, cadence.wakeDelay(ImageUploadRuntime.hasValidatedWifi(context)))
    }
    private val mutex = Mutex()
    private val cancellations = AtomicLong()
    private val http = OkHttpClient.Builder().addInterceptor(GameWorkRuntime.interceptor)
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(5,TimeUnit.SECONDS).readTimeout(10,TimeUnit.SECONDS).callTimeout(15,TimeUnit.SECONDS).build()
    fun cancel() { cancellations.incrementAndGet(); http.dispatcher.cancelAll() }
    private fun exists(context: Context) = File(context.noBackupFilesDir, "video_visits.db").isFile
    private fun enabled(platform: String): Boolean = when (platform) {
        "wechat" -> ChatCaptureSettings.rule("com.tencent.mm").enabled
        "douyin" -> ChatCaptureSettings.rule("com.ss.android.ugc.aweme").enabled
        else -> false
    }
    /** 每次只取至多100条元数据，不读图片、不跨调用缓存；图片回执后下次查询自然放行。 */
    private fun pendingImageIds(context: Context): Set<String> =
        if (!File(context.noBackupFilesDir, "page_capture_outbox.db").isFile) emptySet()
        else PageCaptureOutbox(context).use { pages -> pages.pending(100).map { it.id }.toSet() }

    private fun enabledPlatforms() = setOf("wechat", "douyin").filter(::enabled).toSet()
    fun hasPending(context: Context): Boolean = CollectionConsent.enabled(context) && exists(context) &&
        runCatching { VideoVisitStore(context).use { it.completed(1).isNotEmpty() } }.getOrDefault(false)
    fun hasDue(context: Context): Boolean = ImageUploadRuntime.canUploadChatMessages(context, ServerConfig.baseUrl) && exists(context) &&
        cadence.remainingDelay(ImageUploadRuntime.hasValidatedWifi(context)) == 0L &&
        runCatching { VideoVisitStore(context).use {
            it.nextDeliveryDelay(System.currentTimeMillis(), enabledPlatforms(), pendingImageIds(context)) == 0L
        } }.getOrDefault(false)

    suspend fun flush(context: Context) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val target = ServerConfig.baseUrl
        if (!ImageUploadRuntime.canUploadChatMessages(app, target) || !exists(app) || !mutex.tryLock()) return@withContext
        try {
            if (!hasDue(app) || !cadence.tryStart(ImageUploadRuntime.hasValidatedWifi(app))) return@withContext
            // 限制每批工作量和发起请求的时间窗口，不无限排空离线积压。
            val deadline = SystemClock.elapsedRealtime() + 20_000L
            val epoch = CollectionConsent.epoch
            val targetEpoch = ServerConfig.onlineEpoch
            val policyRevision = ChatCaptureSettings.revision()
            val cancellation = cancellations.get()
            val device = DataCollector.deviceId(app)
            val job = currentCoroutineContext()[Job]
            fun current() = job?.isActive != false && cancellations.get() == cancellation &&
                CollectionConsent.epoch == epoch && ChatCaptureSettings.revision() == policyRevision &&
                target == ServerConfig.baseUrl && targetEpoch == ServerConfig.onlineEpoch &&
                ImageUploadRuntime.canUploadChatMessages(app, target)
            VideoVisitStore(app).use { store ->
                val result = VideoVisitDelivery(store, System::currentTimeMillis,
                    allowed = { platform -> current() && enabled(platform) },
                    prepare = { if (SystemClock.elapsedRealtime() < deadline) ImageUploadRuntime.beginPreparation() else null },
                    send = { row, payload ->
                        val allowed = { current() && enabled(row.platform) }
                        val pkg = if (row.platform == "wechat") "com.tencent.mm" else "com.ss.android.ugc.aweme"
                        val request = Request.Builder().url("$target/api/v1/mobile/video-visits")
                            .header("X-Device-Id", device)
                            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
                        val call = if (SystemClock.elapsedRealtime() < deadline)
                            ImageUploadRuntime.prepareChatCall(app, target, http, request, allowed, preserveCallTimeout = true) else null
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
                ).runOnce(limit = 100, blockedImageIds = pendingImageIds(app))
                if (result.saved + result.discarded + result.failed > 0)
                    android.util.Log.i("VideoVisitSync", "stored=${result.saved} discarded=${result.discarded} retry=${result.failed}")
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { android.util.Log.w("VideoVisitSync", "upload_or_storage_failed") }
        finally { mutex.unlock() }
    }
}
