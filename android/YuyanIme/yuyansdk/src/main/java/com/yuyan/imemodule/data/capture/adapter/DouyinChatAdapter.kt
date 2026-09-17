package com.yuyan.imemodule.data.capture.adapter

import com.yuyan.imemodule.data.capture.model.CapturedConversation
import com.yuyan.imemodule.data.capture.model.CapturedMessage
import com.yuyan.imemodule.data.capture.model.ChatDirection
import com.yuyan.imemodule.data.capture.model.ChatMessageType
import com.yuyan.imemodule.data.capture.model.ChatPlatform
import com.yuyan.imemodule.data.capture.model.ConversationType
import com.yuyan.imemodule.data.capture.ui.IntRect
import com.yuyan.imemodule.data.capture.ui.UiNodeSnapshot
import java.text.Normalizer

/** 抖音 40.5.0 实机结构：聊天层与后台消息列表同时存在，只在聊天层内定位。 */
class DouyinChatAdapter : ChatAppAdapter {
    override val packageName = "com.ss.android.ugc.aweme"

    override fun parse(root: UiNodeSnapshot): ParseResult {
        val container = root.flatten().filter { it.hasId("d_-") }.singleOrNull()
            ?: return ParseResult.Skip(SkipReason.UNSUPPORTED_PAGE)
        val nodes = container.flatten()
        val input = nodes.filter { it.hasId("msg_et") && it.className.orEmpty().endsWith("EditText") }.singleOrNull()
            ?: return ParseResult.Skip(SkipReason.UNSUPPORTED_PAGE)
        val title = nodes.filter { it.hasId("vw3") }.singleOrNull()
            ?: return ParseResult.Skip(SkipReason.AMBIGUOUS_CONVERSATION)
        val body = nodes.filter { it.hasId("jta") }.singleOrNull()
            ?: return ParseResult.Skip(SkipReason.UNSUPPORTED_PAGE)
        val displayName = (title.text ?: title.contentDescription).orEmpty().trim()
        if (displayName.isBlank() || displayName in setOf("消息", "私信", "评论", "直播") ||
            title.bounds.bottom > body.bounds.top || input.bounds.top <= body.bounds.top ||
            input.bounds.bottom > container.bounds.bottom ||
            input.bounds.left < container.bounds.left || input.bounds.right > container.bounds.right
        ) return ParseResult.Skip(SkipReason.UNSUPPORTED_PAGE)
        val bounds = IntRect(
            maxOf(root.bounds.left, body.bounds.left), body.bounds.top,
            minOf(root.bounds.right, body.bounds.right), input.bounds.top,
        )
        if (bounds.right <= bounds.left || bounds.bottom - bounds.top < 200) {
            return ParseResult.Skip(SkipReason.UNSUPPORTED_PAGE)
        }
        val identity = Normalizer.normalize(displayName, Normalizer.Form.NFKC)
        return ParseResult.Success(ParsedViewport(
            conversation = CapturedConversation(
                platform = ChatPlatform.DOUYIN,
                accountKey = "douyin-local",
                externalKey = "visible-title:$identity",
                displayName = displayName,
                // 现场仅能确认聊天标题，不凭标题外观猜测群聊/单聊类型。
                conversationType = ConversationType.UNKNOWN,
                identityConfidence = 0.9,
            ),
            messages = listOf(CapturedMessage(
                conversationKey = null,
                senderKey = "viewport",
                direction = ChatDirection.SYSTEM,
                messageType = ChatMessageType.IMAGE,
                mediaBounds = bounds,
                inputAreaBounds = input.bounds,
                metadata = mapOf("capture_source" to "douyin_screenshot", "capture_kind" to "conversation_screenshot"),
            )),
        ))
    }

    private fun UiNodeSnapshot.hasId(id: String) = viewId == "$packageName:id/$id"
    private fun UiNodeSnapshot.flatten(): List<UiNodeSnapshot> = listOf(this) + children.flatMap { it.flatten() }
}
