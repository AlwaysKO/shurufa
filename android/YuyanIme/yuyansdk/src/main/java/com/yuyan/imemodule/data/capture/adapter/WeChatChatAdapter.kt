package com.yuyan.imemodule.data.capture.adapter

import com.yuyan.imemodule.data.capture.model.CapturedConversation
import com.yuyan.imemodule.data.capture.model.CapturedMessage
import com.yuyan.imemodule.data.capture.model.ChatDirection
import com.yuyan.imemodule.data.capture.model.ChatMessageType
import com.yuyan.imemodule.data.capture.model.ChatPlatform
import com.yuyan.imemodule.data.capture.model.ConversationType
import com.yuyan.imemodule.data.capture.ui.UiNodeSnapshot

/** 微信聊天页截图适配器；只读取标题和输入框位置，不读取消息正文。 */
class WeChatChatAdapter : ChatAppAdapter {
    override val packageName: String = WECHAT_PACKAGE

    override fun parse(root: UiNodeSnapshot): ParseResult {
        val nodes = root.flatten()
        val input = nodes.filter { it.isChatInput() }.maxByOrNull { it.bounds.top }
            ?: return ParseResult.Skip(SkipReason.UNSUPPORTED_PAGE)
        val titleNode = nodes.filter { it.isTitleCandidate(root.bounds.bottom) }
            .sortedWith(compareByDescending<UiNodeSnapshot> { it.viewId.orEmpty().contains("title", true) }
                .thenByDescending { it.bounds.right - it.bounds.left })
            .firstOrNull() ?: return ParseResult.Skip(SkipReason.AMBIGUOUS_CONVERSATION)
        val rawTitle = titleNode.visibleText()?.trim().orEmpty()
        val groupMatch = GROUP_TITLE.matchEntire(rawTitle)
        val conversationType = if (groupMatch != null) ConversationType.GROUP else ConversationType.DIRECT
        val displayName = (groupMatch?.groupValues?.get(1) ?: rawTitle).trim()
        if (displayName.isBlank() || displayName in NON_TITLES) {
            return ParseResult.Skip(SkipReason.AMBIGUOUS_CONVERSATION)
        }

        val screenshotBounds = com.yuyan.imemodule.data.capture.ui.IntRect(
            left = root.bounds.left,
            top = titleNode.bounds.bottom,
            right = root.bounds.right,
            bottom = input.bounds.top,
        )
        if (screenshotBounds.right <= screenshotBounds.left || screenshotBounds.bottom - screenshotBounds.top < 200) {
            return ParseResult.Skip(SkipReason.UNSUPPORTED_PAGE)
        }
        val messages = listOf(CapturedMessage(
            conversationKey = null,
            senderKey = "viewport",
            direction = ChatDirection.SYSTEM,
            messageType = ChatMessageType.IMAGE,
            viewportIndex = 0,
            mediaBounds = screenshotBounds,
            inputAreaBounds = input.bounds,
            metadata = mapOf(
                "capture_source" to "wechat_screenshot",
                "capture_kind" to "conversation_screenshot",
            ),
        ))
        return ParseResult.Success(ParsedViewport(
            conversation = CapturedConversation(
                platform = ChatPlatform.WECHAT,
                accountKey = "wechat-local",
                externalKey = "${conversationType.wireName}-visible-title:$displayName",
                displayName = displayName,
                conversationType = conversationType,
                identityConfidence = if (titleNode.viewId.orEmpty().contains("title", true)) 0.92 else 0.82,
            ),
            messages = messages,
        ))
    }

    private fun UiNodeSnapshot.visibleText(): String? = text ?: contentDescription

    private fun UiNodeSnapshot.isChatInput(): Boolean =
        className.orEmpty().endsWith("EditText") || viewId.orEmpty().containsAny("chatting_content_et", "chat_input")

    private fun UiNodeSnapshot.isTitleCandidate(screenBottom: Int): Boolean {
        val value = visibleText()?.trim().orEmpty()
        if (value.isEmpty() || children.isNotEmpty() || bounds.top >= screenBottom * 0.18) return false
        if (value in CONTROL_TEXT || TIME_PATTERN.matches(value)) return false
        return viewId.orEmpty().contains("title", true) || (bounds.right - bounds.left) > 160
    }

    private fun String.containsAny(vararg values: String): Boolean = values.any { contains(it, ignoreCase = true) }
    private fun UiNodeSnapshot.flatten(): List<UiNodeSnapshot> = listOf(this) + children.flatMap { it.flatten() }

    private companion object {
        const val WECHAT_PACKAGE = "com.tencent.mm"
        val GROUP_TITLE = Regex("^(.+?)[（(](\\d+)[）)]$")
        val TIME_PATTERN = Regex("^(?:\\d{1,2}:\\d{2}|昨天|星期[一二三四五六日天]|\\d{1,2}月\\d{1,2}日).*$")
        val CONTROL_TEXT = setOf("发送", "按住 说话", "切换到键盘", "返回", "更多")
        val NON_TITLES = setOf("微信", "聊天信息", "")
    }
}
