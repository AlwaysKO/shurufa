package com.yuyan.imemodule.data.capture.page

import android.util.Base64
import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

internal data class PageUploadResponse(val status: Int, val body: String?)
internal data class PageDeliveryResult(val saved: Int, val discarded: Int, val failed: Int)

/** 只上传已安全分类并持久化的页面图；每轮两条，回执失败保留并退避。 */
internal class PageCaptureDelivery(
    private val outbox: PageCaptureOutbox,
    private val now: () -> Long,
    private val allowed: (String) -> Boolean,
    private val prepare: () -> Closeable?,
    private val permit: (Long) -> Closeable?,
    private val send: suspend (PendingPageCapture, ByteArray) -> PageUploadResponse?,
    private val preparationAllowed: () -> Boolean = { true },
) {
    suspend fun runOnce(): PageDeliveryResult {
        var saved = 0; var discarded = 0; var failed = 0
        for (row in outbox.due(now()).filter { allowed(it.packageName) }.take(2)) {
            currentCoroutineContext().ensureActive()
            if (!allowed(row.packageName)) break
            try {
                val preparation = prepare() ?: break
                val payload = try {
                    if (!allowed(row.packageName) || !preparationAllowed()) break
                    val bytes = outbox.image(row.id) ?: error("Missing page image")
                    if (!allowed(row.packageName) || !preparationAllowed()) break
                    JSONObject().put("id", row.id).put("package_name", row.packageName)
                        .put("kind", row.kind.name.lowercase(java.util.Locale.ROOT)).put("captured_at", row.capturedAt)
                        .put("width", row.width).put("height", row.height).put("sha256", row.sha256)
                        .put("mime_type", "image/webp").put("file_base64", Base64.encodeToString(bytes, Base64.NO_WRAP)).toString().toByteArray(Charsets.UTF_8)
                } finally { preparation.close() }
                currentCoroutineContext().ensureActive()
                if (!allowed(row.packageName)) break
                val upload = permit(payload.size.toLong()) ?: break
                val response = try {
                    if (!allowed(row.packageName)) break
                    send(row, payload)
                } finally { upload.close() }
                currentCoroutineContext().ensureActive()
                if (!allowed(row.packageName)) break
                val ack = validatePageReceipt(response, row)
                if (ack != null && outbox.acknowledge(row) { allowed(row.packageName) }) {
                    if (ack) discarded++ else saved++
                } else {
                    failed++; outbox.defer(row, now())
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed++; outbox.defer(row, now()) }
        }
        return PageDeliveryResult(saved, discarded, failed)
    }
}

/** null是无效回执；false是已存储；true是服务器明确关闭保存，不冒充已存储。 */
internal fun validatePageReceipt(response: PageUploadResponse?, row: PendingPageCapture): Boolean? = runCatching {
    if (response == null || response.status !in 200..299) return null
    val body = response.body ?: return null
    if (body.toByteArray(Charsets.UTF_8).size > 4096) return null
    val parser = org.json.JSONTokener(body)
    val r = parser.nextValue() as? JSONObject ?: return null
    if (parser.nextClean() != '\u0000') return null
    if (r.keys().asSequence().any { it !in setOf("ok", "id", "sha256", "discarded") } ||
        r.opt("ok") != true || r.opt("id") != row.id || r.opt("sha256") != row.sha256 ||
        (r.has("discarded") && r.opt("discarded") !is Boolean)) return null
    r.opt("discarded") == true
}.getOrNull()
