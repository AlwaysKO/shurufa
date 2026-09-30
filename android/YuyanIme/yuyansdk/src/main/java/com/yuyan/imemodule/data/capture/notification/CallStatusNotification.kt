package com.yuyan.imemodule.data.capture.notification

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun isOngoingCallStatus(text: String?): Boolean = text?.trim() in setOf("语音通话中", "视频通话中")

/** Null means a non-empty batch consisted entirely of ignored state notifications. */
internal fun filterCallStatusNotifications(batch: JsonObject): JsonObject? {
    val conversation = batch["conversation"] as? JsonObject ?: return batch
    if (conversation.text("platform") !in setOf("wechat", "qq", "douyin")) return batch
    val messages = batch["messages"] as? JsonArray ?: return batch
    val kept = messages.filterNot { element ->
        val message = element as? JsonObject ?: return@filterNot false
        val metadata = message["metadata"] as? JsonObject
        message.text("direction") == "incoming" && metadata?.text("capture_source") == "notification"
            && !isMessagingNotification(metadata) && isOngoingCallStatus(message.text("text"))
    }
    if (kept.size == messages.size) return batch
    if (kept.isEmpty()) return null
    return JsonObject(batch + ("messages" to JsonArray(kept)))
}

private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.content

private fun isMessagingNotification(metadata: JsonObject?): Boolean {
    if (metadata == null) return false
    if ("notification_messaging_style" in metadata) return metadata.text("notification_messaging_style") == "true"
    // 旧版没有样式字段，但仅为 MessagingStyle 消息保存该时间戳。
    val timestamp = metadata.text("notification_message_timestamp") ?: return false
    if (!timestamp.matches(Regex("[0-9]+"))) return false
    return timestamp.toLongOrNull()?.let { it in 1..9007199254740991L } == true
}
