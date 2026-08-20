package com.yuyan.imemodule.data.capture.model

enum class ChatPlatform(val wireName: String) {
    WECHAT("wechat"),
    QQ("qq"),
    DOUYIN("douyin"),
}

enum class ConversationType(val wireName: String) {
    DIRECT("direct"),
    GROUP("group"),
    UNKNOWN("unknown"),
}

data class CapturedConversation(
    val platform: ChatPlatform,
    val accountKey: String,
    val externalKey: String?,
    val displayName: String? = null,
    val conversationType: ConversationType,
    val identityConfidence: Double,
)

fun CapturedConversation.stableKeyOrNull(): String? {
    val account = accountKey.trim().takeIf { it.isNotEmpty() } ?: return null
    val external = externalKey?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return listOf(platform.wireName, account, conversationType.wireName, external)
        .joinToString("|")
}
