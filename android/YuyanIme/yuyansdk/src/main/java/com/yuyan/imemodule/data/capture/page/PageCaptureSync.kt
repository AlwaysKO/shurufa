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
import okhttp3.ResponseBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** 由既有同步循环唤醒；不自建定时器、不走USB/流量镜像。 */
internal object PageCaptureSync {
    private val mutex = Mutex()
    private val cancellations = AtomicLong()
    private val http = OkHttpClient.Builder().addInterceptor(GameWorkRuntime.interceptor)
        .followRedirects(false).followSslRedirects(false).build()
    fun cancel() { cancellations.incrementAndGet(); http.dispatcher.cancelAll() }
    private fun exists(context: Context) = File(context.noBackupFilesDir, "page_capture_outbox.db").isFile
    fun hasPending(context: Context): Boolean = CollectionConsent.enabled(context) && exists(context) &&
        runCatching { PageCaptureOutbox(context).use { it.pending(1).isNotEmpty() } }.getOrDefault(false)
    fun hasDue(context: Context): Boolean = ImageUploadRuntime.isBackgroundWorkAllowed() &&
        CollectionConsent.enabled(context) && exists(context) && runCatching {
            PageCaptureOutbox(context).use { s -> s.due(System.currentTimeMillis()).any { ChatCaptureSettings.rule(it.packageName).enabled } }
        }.getOrDefault(false)

    suspend fun flush(context: Context) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val target = ServerConfig.baseUrl
        if (CollectionConsent.enabled(app) && ImageUploadRuntime.isBackgroundWorkAllowed() &&
            !ImageUploadRuntime.hasValidatedWifi(app) && exists(app)) {
            runCatching {
                PageCaptureOutbox(app).use { outbox ->
                    outbox.pending(100).map { it.packageName }.distinct().forEach {
                        PageCaptureDiagnostics.upload(app, it, "waiting_wifi")
                    }
                }
            }
        }
        if (!ImageUploadRuntime.canUploadScreenshot(app, target) || !exists(app) || !mutex.tryLock()) return@withContext
        try {
            val epoch = CollectionConsent.epoch
            val targetEpoch = ServerConfig.onlineEpoch
            val policyRevision = ChatCaptureSettings.revision()
            val cancellation = cancellations.get()
            val device = DataCollector.deviceId(app)
            val job = currentCoroutineContext()[Job]
            fun current() = job?.isActive != false && cancellations.get() == cancellation &&
                CollectionConsent.epoch == epoch && ChatCaptureSettings.revision() == policyRevision && target == ServerConfig.baseUrl && targetEpoch == ServerConfig.onlineEpoch &&
                ImageUploadRuntime.canUploadScreenshot(app, target)
            PageCaptureOutbox(app).use { outbox ->
                val result = PageCaptureDelivery(outbox, System::currentTimeMillis,
                    allowed = { pkg -> current() && ChatCaptureSettings.rule(pkg).enabled },
                    prepare = { ImageUploadRuntime.beginPreparation() },
                    permit = { bytes -> if (current()) ImageUploadRuntime.tryStartImage(app, target, bytes) else null },
                    preparationAllowed = ImageUploadRuntime::isBackgroundWorkAllowed,
                    send = { row, payload ->
                        // 准备阶段已完成Base64/UTF-8编码；上传阶段不重读或解析大JSON。
                        val pkg = row.packageName
                        val allowed = { current() && ChatCaptureSettings.rule(pkg).enabled }
                        val request = Request.Builder().url("$target/api/v1/mobile/page-captures")
                            .header("X-Device-Id", device)
                            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
                        val call = ImageUploadRuntime.prepareChatCall(app, target, http, request, allowed)
                        if (call == null) null else try {
                            call.execute().use { response ->
                                PageUploadResponse(response.code, if (response.isSuccessful && allowed()) response.body?.let(::readPageReceipt) else null).also { reply ->
                                    if (allowed()) {
                                        val receipt = validatePageReceipt(reply, row)
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
                    android.util.Log.i("PageCaptureSync", "stored=${result.saved} discarded=${result.discarded} retry=${result.failed}")
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { android.util.Log.w("PageCaptureSync", "upload_or_storage_failed") }
        finally { mutex.unlock() }
    }
}

/** 回执上限4KiB，未知Content-Length同样有界；禁止直接string()读任意响应。 */
internal fun readPageReceipt(body: ResponseBody): String? {
    if (body.contentLength() > 4096) return null
    val output = java.io.ByteArrayOutputStream()
    body.byteStream().use { stream ->
        val chunk = ByteArray(1024)
        while (output.size() <= 4096) {
            val size = stream.read(chunk, 0, minOf(chunk.size, 4097 - output.size()))
            if (size < 0) break
            if (size == 0) return null
            output.write(chunk, 0, size)
        }
    }
    if (output.size() > 4096) return null
    return runCatching { Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .decode(java.nio.ByteBuffer.wrap(output.toByteArray())).toString() }.getOrNull()
}
