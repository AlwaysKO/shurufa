package com.yuyan.imemodule.data.capture.model

enum class ChatDirection(val wireName: String) {
    INCOMING("incoming"),
    OUTGOING("outgoing"),
    SYSTEM("system"),
}

enum class ChatMessageType(val wireName: String) {
    TEXT("text"),
    EMOJI("emoji"),
    IMAGE("image"),
    STICKER("sticker"),
    VIDEO("video"),
    VOICE("voice"),
    LINK("link"),
    FILE("file"),
    MUSIC("music"),
    LOCATION("location"),
    CONTACT("contact"),
    MINI_APP("mini_app"),
    RED_PACKET("red_packet"),
    TRANSFER("transfer"),
    SYSTEM("system"),
    RECALLED("recalled"),
    UNKNOWN("unknown"),
}

data class CapturedMessage(
    val conversationKey: String?,
    val senderKey: String,
    val senderName: String? = null,
    val direction: ChatDirection,
    val messageType: ChatMessageType,
    val text: String? = null,
    val displayedTime: String? = null,
    val occurredAt: String? = null,
    val assetSha256: List<String> = emptyList(),
    val previousContentFingerprint: String? = null,
    val nextContentFingerprint: String? = null,
    val sameContentOrdinal: Int = 0,
    val viewportIndex: Int? = null,
    val metadata: Map<String, String> = emptyMap(),
)
