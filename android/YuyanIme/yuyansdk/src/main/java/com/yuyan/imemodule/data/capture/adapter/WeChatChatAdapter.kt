package com.yuyan.imemodule.data.capture.adapter

import com.yuyan.imemodule.data.capture.model.CapturedConversation
import com.yuyan.imemodule.data.capture.model.CapturedMessage
import com.yuyan.imemodule.data.capture.model.ChatDirection
import com.yuyan.imemodule.data.capture.model.ChatMessageType
import com.yuyan.imemodule.data.capture.model.ChatPlatform
import com.yuyan.imemodule.data.capture.model.ConversationType
import com.yuyan.imemodule.data.capture.ui.UiNodeSnapshot

/** 微信聊天页截图适配器；只读取标题和输入框位置，不读取消息正文。 */
class WeChatChatAdapter(private val ruleProvider: () -> ChatCaptureRule = { ChatCapturePolicy.builtIn().rule("com.tencent.mm", 0)!! }) : ChatAppAdapter {
    override val packageName: String = WECHAT_PACKAGE

    override fun parse(root: UiNodeSnapshot): ParseResult {
        if (!root.visibleToUser || root.flatten().any { it.password }) return ParseResult.Skip(SkipReason.UNSUPPORTED_PAGE)
        if (com.yuyan.imemodule.data.capture.media.isWechatNonChatTree(root)) return ParseResult.Skip(SkipReason.UNSUPPORTED_PAGE)
        val known = parseKnownPage(root)
        if (known is ParseResult.Success) return known
        // 配置只提供候选，旧锚点部分保留时也须完整通过同层多证据证明。
        val structural = DouyinChatAdapter(ruleProvider, WECHAT_PACKAGE).parse(root)
        if (structural !is ParseResult.Success) return known
        val viewport = structural.viewport
        return ParseResult.Success(viewport.copy(
            conversation = viewport.conversation.copy(platform = ChatPlatform.WECHAT, accountKey = "wechat-local"),
            messages = viewport.messages.map { it.copy(metadata = it.metadata + ("capture_source" to "wechat_screenshot")) },
        ))
    }

    private fun parseKnownPage(root: UiNodeSnapshot): ParseResult {
        val rawNodes = root.flatten()
        val inputs = rawNodes.filter { it.isChatInput() }
        if (inputs.size > 1) return ParseResult.Skip(SkipReason.AMBIGUOUS_CONVERSATION)
        val inputNode = inputs.singleOrNull()
        val nodes = if (inputNode == null) rawNodes else sameLayerNodes(root, inputNode)
        if (inputNode != null && rawNodes.any { it.viewId.orEmpty().contains("chatting_title") } &&
            nodes.none { it.viewId.orEmpty().contains("chatting_title") }) return ParseResult.Skip(SkipReason.UNSUPPORTED_PAGE)
        // 朋友圈评论/发现搜索也有EditText，不能把“有输入框”当成聊天页身份。
        val explicitChat = nodes.any { it.viewId.orEmpty().containsAny("chatting_title", "chatting_content_et", "chat_input") }
        if (!explicitChat) {
            fixedPageTitle(root, if (nodes.isEmpty()) rawNodes else nodes)?.let { return parseFixedPage(root, it) }
            return ParseResult.Skip(SkipReason.UNSUPPORTED_PAGE)
        }
        val input = nodes.filter { it.isChatInput() || (it.visibleText()?.replace(" ", "") == "按住说话" && it.bounds.top > root.bounds.top + (root.bounds.bottom - root.bounds.top) / 2) }.maxByOrNull { it.bounds.top }
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
            titleBounds = titleNode.bounds,
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

    /** 固定页面不要求聊天输入框；仅在顶部有明确已知标题时采集，发现页仍跳过。 */
    private fun fixedPageTitle(root: UiNodeSnapshot, nodes: List<UiNodeSnapshot>): String? {
        val title = nodes.filter { it.children.isEmpty() && it.bounds.top >= root.bounds.top &&
            it.bounds.bottom <= root.bounds.top + (root.bounds.right - root.bounds.left) * 0.22 &&
            !it.visibleText().isNullOrBlank() && it.visibleText() !in CONTROL_TEXT &&
            !TIME_PATTERN.matches(it.visibleText().orEmpty()) }
            .sortedWith(compareByDescending<UiNodeSnapshot> { it.viewId.orEmpty().contains("title", true) }
                .thenBy { kotlin.math.abs((it.bounds.left + it.bounds.right) / 2.0 - (root.bounds.left + root.bounds.right) / 2.0) })
            .firstOrNull()
        return com.yuyan.imemodule.data.capture.media.canonicalWechatPageTitle(title?.visibleText())
    }

    private fun parseFixedPage(root: UiNodeSnapshot, title: String): ParseResult {
        if (title == "发现") return ParseResult.Skip(SkipReason.UNSUPPORTED_PAGE)
        val identity = com.yuyan.imemodule.data.capture.media.WechatTitleStabilizer().observe(title, null, 0)
        return ParseResult.Success(ParsedViewport(
            conversation = CapturedConversation(ChatPlatform.WECHAT, "wechat-empty-tree", identity.externalKey,
                title, ConversationType.UNKNOWN, identity.confidence),
            messages = listOf(CapturedMessage(conversationKey = null, senderKey = "${identity.externalKey}:viewport",
                direction = ChatDirection.SYSTEM, messageType = ChatMessageType.IMAGE, viewportIndex = 0,
                mediaBounds = root.bounds, metadata = mapOf("capture_source" to "wechat_page_screenshot",
                    "capture_kind" to "conversation_screenshot", "conversation_identity_status" to "confirmed"))),
        ))
    }

    private fun sameLayerNodes(root: UiNodeSnapshot, input: UiNodeSnapshot): List<UiNodeSnapshot> {
        fun path(node: UiNodeSnapshot): List<UiNodeSnapshot>? {
            if (node === input) return listOf(node)
            for (child in node.children) path(child)?.let { return listOf(node) + it }
            return null
        }
        val ancestors = path(root) ?: return emptyList()
        for (scope in ancestors.dropLast(1).asReversed()) {
            val branch = ancestors[ancestors.indexOfFirst { it === scope } + 1]
            val nodes = scope.children.flatMap { other ->
                if (other === branch) other.flatten()
                else if (other.bounds.left <= input.bounds.left && other.bounds.right >= input.bounds.right &&
                    other.bounds.top <= input.bounds.top && other.bounds.bottom >= input.bounds.bottom) emptyList()
                else other.flatten()
            }
            if (nodes.any { it.viewId.orEmpty().contains("chatting_title") }) return nodes
        }
        return emptyList()
    }

    private fun UiNodeSnapshot.visibleText(): String? = text ?: contentDescription

    private fun UiNodeSnapshot.isChatInput(): Boolean =
        editable || className.orEmpty().endsWith("EditText") || viewId.orEmpty().containsAny("chatting_content_et", "chat_input")

    private fun UiNodeSnapshot.isTitleCandidate(screenBottom: Int): Boolean {
        val value = visibleText()?.trim().orEmpty()
        if (value.isEmpty() || children.isNotEmpty() || bounds.top >= screenBottom * 0.18) return false
        if (value in CONTROL_TEXT || TIME_PATTERN.matches(value)) return false
        return viewId.orEmpty().contains("title", true) || (bounds.right - bounds.left) > 160
    }

    private fun String.containsAny(vararg values: String): Boolean = values.any { contains(it, ignoreCase = true) }
    private fun UiNodeSnapshot.flatten(): List<UiNodeSnapshot> =
        if (!visibleToUser) emptyList() else listOf(this) + children.flatMap { it.flatten() }

    private companion object {
        const val WECHAT_PACKAGE = "com.tencent.mm"
        val GROUP_TITLE = Regex("^(.+?)[（(](\\d+)[）)]$")
        val TIME_PATTERN = Regex("^(?:\\d{1,2}:\\d{2}|昨天|星期[一二三四五六日天]|\\d{1,2}月\\d{1,2}日).*$")
        val CONTROL_TEXT = setOf("发送", "按住 说话", "切换到键盘", "返回", "更多")
        val NON_TITLES = setOf("微信", "聊天信息", "")
    }
}
