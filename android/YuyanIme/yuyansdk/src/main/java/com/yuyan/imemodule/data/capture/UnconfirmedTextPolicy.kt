package com.yuyan.imemodule.data.capture

import kotlinx.serialization.json.*

internal fun shouldDiscardUnconfirmedText(
    identityConfidence: Double?, messageType: String?,
    metadata: Map<String, String>, hasResource: Boolean,
): Boolean {
    if (messageType != "text" || hasResource || !metadata["asset_capture_deferred"].isNullOrBlank() ||
        metadata["asset_capture_failed"] == "true" || "notification_media_readable" in metadata) return false
    return identityConfidence?.let { it.isFinite() && it < .8 } == true ||
        metadata["conversation_identity_status"] in setOf("pending", "truncated") ||
        metadata["identity_unavailable"] == "true"
}

/** 只结束明确无资源的待确认文字；未知格式交给原校验，不误删媒体依赖。 */
internal fun filterUnconfirmedTextMessages(batch: JsonObject): JsonObject? {
    val conversation = batch["conversation"] as? JsonObject ?: return batch
    val messages = batch["messages"] as? JsonArray ?: return batch
    val confidence = (conversation["identity_confidence"] as? JsonPrimitive)?.doubleOrNull
    val kept = messages.filterNot { element ->
        val message = element as? JsonObject ?: return@filterNot false
        val rawMetadata = message["metadata"]
        if (rawMetadata != null && rawMetadata != JsonNull && rawMetadata !is JsonObject) return@filterNot false
        val metadata = (rawMetadata as? JsonObject)?.mapNotNull { (key, value) ->
            (value as? JsonPrimitive)?.contentOrNull?.let { key to it }
        }?.toMap().orEmpty()
        val references = message["asset_sha256"]
        val hasResource = when (references) {
            null, JsonNull -> false
            is JsonArray -> references.isNotEmpty()
            else -> true // 不能把格式异常的资源字段当作明确无图。
        }
        shouldDiscardUnconfirmedText(confidence, (message["message_type"] as? JsonPrimitive)?.contentOrNull, metadata, hasResource)
    }
    if (kept.size == messages.size) return batch
    return if (kept.isEmpty()) null else JsonObject(batch + ("messages" to JsonArray(kept)))
}
