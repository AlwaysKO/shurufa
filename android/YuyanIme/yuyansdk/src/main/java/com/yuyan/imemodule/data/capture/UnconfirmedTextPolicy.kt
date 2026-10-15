package com.yuyan.imemodule.data.capture

import kotlinx.serialization.json.*

internal fun shouldDiscardUnconfirmedText(
    identityConfidence: Double?, messageType: String?,
    metadata: Map<String, String>, hasResource: Boolean,
): Boolean {
    val pending = identityConfidence?.let { it.isFinite() && it < .8 } == true ||
        metadata["conversation_identity_status"] in setOf("pending", "truncated") ||
        metadata["identity_unavailable"] == "true"
    if (!pending) return false
    // 发起补截图、通知缩略图或“截图失败”标记都不是实际聊天截图。
    if (messageType != "image" || !hasResource) return true
    return when (metadata["capture_source"]) {
        "wechat_screenshot", "douyin_screenshot", "qq_screenshot" -> false
        "wechat_empty_tree_screenshot", "notification_screenshot_fallback" -> metadata["screenshot_chat_verified"] != "true"
        else -> true
    }
}

/** 待确认会话必须附带实际聊天截图；真实截图依赖暂不可用时仍交原队列重试。 */
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
