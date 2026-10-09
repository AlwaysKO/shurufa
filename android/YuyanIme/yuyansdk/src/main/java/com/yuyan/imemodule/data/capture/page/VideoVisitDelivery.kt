package com.yuyan.imemodule.data.capture.page

import java.io.Closeable
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONObject

internal data class VideoDeliveryResult(val saved: Int, val discarded: Int, val failed: Int)

/** 已结束访问的小型元数据；原始页面标识与本机单调时钟不离开设备。 */
internal fun videoVisitPayload(visit: VideoVisit): JSONObject {
    require(visit.reason != null)
    return JSONObject().put("id", visit.id).put("platform", visit.platform)
        .put("entered_at", visit.enteredAt).put("ended_at", visit.endedAt ?: JSONObject.NULL)
        .put("duration_ms", visit.durationMillis ?: JSONObject.NULL)
        .put("exit_reason", visit.reason.name.lowercase(Locale.ROOT)).put("complete", visit.complete)
        .put("observation_kind", visit.observationKind.name.lowercase(Locale.ROOT))
        .put("first_image_id", visit.firstImage ?: JSONObject.NULL)
        .put("last_image_id", visit.lastImage ?: JSONObject.NULL)
}

internal class VideoVisitDelivery(
    private val store: VideoVisitStore,
    private val now: () -> Long,
    private val allowed: (String) -> Boolean,
    private val prepare: () -> Closeable?,
    private val send: suspend (VideoVisit, ByteArray) -> PageUploadResponse?,
) {
    suspend fun runOnce(): VideoDeliveryResult {
        var saved = 0; var discarded = 0; var failed = 0
        val platforms = setOf("wechat", "douyin").filter { allowed(it) }.toSet()
        for (row in store.due(now(), limit = 2, platforms = platforms)) {
            currentCoroutineContext().ensureActive()
            if (!allowed(row.platform)) break
            try {
                val preparation = prepare() ?: break
                val payload = try {
                    if (!allowed(row.platform)) break
                    videoVisitPayload(row).toString().toByteArray(Charsets.UTF_8)
                } finally { preparation.close() }
                if (!allowed(row.platform)) break
                val response = send(row, payload)
                currentCoroutineContext().ensureActive()
                if (!allowed(row.platform)) break
                val receipt = validateVideoVisitReceipt(response, row)
                if (receipt != null && store.acknowledge(row) { allowed(row.platform) }) {
                    if (receipt) discarded++ else saved++
                } else { failed++; store.defer(row, now()) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed++; store.defer(row, now()) }
        }
        return VideoDeliveryResult(saved, discarded, failed)
    }
}

/** 严格完整回显，不只匹配 ID；服务端明确弃存与实际保存分别计数。 */
internal fun validateVideoVisitReceipt(response: PageUploadResponse?, visit: VideoVisit): Boolean? = runCatching {
    if (response == null || response.status !in 200..299) return null
    val text = response.body ?: return null
    if (text.toByteArray(Charsets.UTF_8).size > 4096) return null
    val receipt = Json.parseToJsonElement(text) as? JsonObject ?: return null
    if (receipt.keys.any { it !in setOf("ok", "record", "discarded") } || receipt["ok"] != JsonPrimitive(true)) return null
    if (receipt.containsKey("discarded") && receipt["discarded"] != JsonPrimitive(true)) return null
    if (receipt["record"] != Json.parseToJsonElement(videoVisitPayload(visit).toString())) return null
    receipt["discarded"] == JsonPrimitive(true)
}.getOrNull()
