package com.yuyan.imemodule.data.capture

import com.yuyan.imemodule.data.capture.media.ScreenshotConversationIdentity
import com.yuyan.imemodule.data.capture.model.CapturedConversation
import com.yuyan.imemodule.data.capture.model.CapturedMessage
import com.yuyan.imemodule.data.capture.model.ChatMessageType
import com.yuyan.imemodule.data.capture.model.ChatPlatform
import com.yuyan.imemodule.data.capture.model.ConversationType

internal const val WECHAT_LIST_HASH = "wechat_list_content_sha256"
internal val wechatListConversation = CapturedConversation(
    ChatPlatform.WECHAT, "wechat-empty-tree", "screenshot-v2:wechat-page:" + sha256("微信".toByteArray(Charsets.UTF_8)),
    "微信", ConversationType.UNKNOWN, 0.95,
)

internal fun ScreenshotConversationIdentity.isWechatConversationList(): Boolean =
    isChatPage && status == "confirmed" && source == "wechat_page_title" && displayName == "微信"

/** 候选摘要必须和首帧的已确认列表身份一起传递，不能借后续确认给旧图补上。 */
internal fun wechatListMetadata(confirmed: Boolean, hash: String?): Map<String, String> =
    if (confirmed && hash?.matches(Regex("[a-f0-9]{64}")) == true) mapOf(
        WECHAT_LIST_HASH to hash,
        "conversation_identity_status" to "confirmed",
        "conversation_identity_source" to "wechat_page_title",
    ) else emptyMap()

internal fun verifiedWechatListHash(conversation: CapturedConversation, message: CapturedMessage): String? {
    if (conversation.platform != ChatPlatform.WECHAT || conversation.displayName != "微信" ||
        conversation.identityConfidence < 0.8 || message.messageType != ChatMessageType.IMAGE ||
        message.metadata["conversation_identity_status"] != "confirmed" ||
        message.metadata["conversation_identity_source"] != "wechat_page_title" ||
        message.metadata["capture_source"] !in setOf("wechat_page_screenshot", "wechat_empty_tree_screenshot", "notification_screenshot_fallback")) return null
    return message.metadata[WECHAT_LIST_HASH]?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }
}
