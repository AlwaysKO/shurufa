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

/** QQ 9.3.60实机：标题与聊天设置同属顶部栏，输入框位于独立底部工具栏。 */
class QqChatAdapter : ChatAppAdapter {
    override val packageName = "com.tencent.mobileqq"

    override fun parse(root: UiNodeSnapshot): ParseResult {
        val nodes = root.flatten()
        val header = nodes.filter { it.hasId("jo9") }.singleOrNull()
            ?: return ParseResult.Skip(SkipReason.UNSUPPORTED_PAGE)
        val headerNodes = header.flatten()
        val title = headerNodes.filter { it.hasId("3cb") }.singleOrNull()
            ?: return ParseResult.Skip(SkipReason.AMBIGUOUS_CONVERSATION)
        val settings = headerNodes.filter { it.hasId("19c") }.singleOrNull()
            ?: return ParseResult.Skip(SkipReason.UNSUPPORTED_PAGE)
        val toolbar = nodes.filter { it.hasId("dmj") }.singleOrNull()
            ?: return ParseResult.Skip(SkipReason.UNSUPPORTED_PAGE)
        val input = toolbar.flatten().filter { it.hasId("input") && it.className.orEmpty().endsWith("EditText") }
            .singleOrNull() ?: return ParseResult.Skip(SkipReason.UNSUPPORTED_PAGE)
        val name = (title.text ?: title.contentDescription).orEmpty().trim()
        if (name.isBlank() || name in setOf("消息", "联系人", "搜索", "聊天设置") ||
            !header.bounds.contains(title.bounds) || !header.bounds.contains(settings.bounds) ||
            !root.bounds.contains(header.bounds) || !root.bounds.contains(toolbar.bounds) ||
            !toolbar.bounds.contains(input.bounds)
        ) return ParseResult.Skip(SkipReason.UNSUPPORTED_PAGE)
        val bounds = IntRect(root.bounds.left, header.bounds.bottom, root.bounds.right, toolbar.bounds.top)
        if (bounds.right <= bounds.left || bounds.bottom - bounds.top < 200) {
            return ParseResult.Skip(SkipReason.UNSUPPORTED_PAGE)
        }
        return ParseResult.Success(ParsedViewport(
            titleBounds = title.bounds,
            conversation = CapturedConversation(
                platform = ChatPlatform.QQ,
                accountKey = "qq-local",
                externalKey = "visible-title:${Normalizer.normalize(name, Normalizer.Form.NFKC)}",
                displayName = name,
                conversationType = ConversationType.UNKNOWN,
                identityConfidence = 0.9,
            ),
            messages = listOf(CapturedMessage(
                conversationKey = null,
                senderKey = "viewport",
                direction = ChatDirection.SYSTEM,
                messageType = ChatMessageType.IMAGE,
                mediaBounds = bounds,
                inputAreaBounds = toolbar.bounds,
                metadata = mapOf("capture_source" to "qq_screenshot", "capture_kind" to "conversation_screenshot"),
            )),
        ))
    }

    private fun UiNodeSnapshot.hasId(id: String) = viewId == "$packageName:id/$id"
    private fun UiNodeSnapshot.flatten(): List<UiNodeSnapshot> = listOf(this) + children.flatMap { it.flatten() }
    private fun IntRect.contains(other: IntRect): Boolean =
        other.right > other.left && other.bottom > other.top &&
            other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom
}
