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

enum class DouyinPageStatus(val label: String) {
    MATCHED_LEGACY("已识别聊天页（原适配）"),
    MATCHED_STRUCTURE("已识别聊天页（结构兼容）"),
    NO_UNIQUE_INPUT("未找到唯一聊天输入框"),
    MISSING_CHAT_HEADER("缺少返回与明确的聊天设置标识"),
    NON_CHAT_PAGE("非聊天页面或评论输入框"),
    AMBIGUOUS_TITLE("聊天标题缺失或存在歧义"),
    MISSING_MESSAGE_LIST("无法确认聊天消息区域"),
    INVALID_BOUNDS("页面区域不完整或存在重叠"),
    AMBIGUOUS_CHAT("存在多个聊天层"),
}

internal data class DouyinParseOutcome(val result: ParseResult, val status: DouyinPageStatus)

/** 原版 ID 精确匹配优先；结构回退只认同一页面中的多项聊天证据。 */
class DouyinChatAdapter : ChatAppAdapter {
    override val packageName = "com.ss.android.ugc.aweme"

    override fun parse(root: UiNodeSnapshot): ParseResult = inspect(root).result

    internal fun inspect(root: UiNodeSnapshot): DouyinParseOutcome {
        if (root.flatten().count { it.hasId("d_-") } > 1) return skipped(DouyinPageStatus.AMBIGUOUS_CHAT)
        val legacy = parseLegacy(root)
        if (legacy is ParseResult.Success) return DouyinParseOutcome(legacy, DouyinPageStatus.MATCHED_LEGACY)
        return parseStructure(root)
    }

    private fun parseLegacy(root: UiNodeSnapshot): ParseResult {
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
        return viewport(title, input, bounds)
    }

    private fun viewport(title: UiNodeSnapshot, input: UiNodeSnapshot, bounds: IntRect): ParseResult.Success {
        val displayName = title.label()
        val identity = Normalizer.normalize(displayName, Normalizer.Form.NFKC)
        return ParseResult.Success(ParsedViewport(
            titleBounds = title.bounds,
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
    private fun parseStructure(root: UiNodeSnapshot): DouyinParseOutcome {
        // 不用坐标猜前后台层；多个输入框时宁可等待页面稳定。
        val paths = mutableListOf<List<UiNodeSnapshot>>()
        fun visit(node: UiNodeSnapshot, ancestors: List<UiNodeSnapshot>) {
            val path = ancestors + node
            if (node.className.orEmpty().endsWith("EditText")) paths += path
            node.children.forEach { visit(it, path) }
        }
        visit(root, emptyList())
        val path = paths.singleOrNull() ?: return skipped(DouyinPageStatus.NO_UNIQUE_INPUT)
        val input = path.last()
        if (Regex("评论|弹幕|搜索|comment|search", RegexOption.IGNORE_CASE)
                .containsMatchIn(input.contentDescription.orEmpty())) return skipped(DouyinPageStatus.NON_CHAT_PAGE)
        // 使用最靠近输入框的完整聊天层，禁止借后台消息列表的标题补齐前台证据。
        for (scope in path.dropLast(1).asReversed()) {
            val inputBranch = path[path.indexOfFirst { it === scope } + 1]
            // 对全部证据使用同一页面边界：整页兄弟层不能贡献标题、列表或语音按钮。
            val nodes = scope.children.flatMap { branch ->
                if (branch === inputBranch) branch.flatten()
                else if (branch.bounds.contains(input.bounds)) emptyList()
                else branch.flatten().filterNot { inputBranch.bounds.contains(it.bounds) }
            }.filter { it.bounds.width() > 0 && it.bounds.height() > 0 && scope.bounds.contains(it.bounds) }
            val hasVoiceComposer = nodes.any {
                it.label() in setOf("切换到语音输入", "切换到语音", "按住说话") &&
                    it.bounds.overlapsVertically(input.bounds) && it.bounds.right <= input.bounds.left &&
                    it.bounds.width() > 0 && scope.bounds.contains(it.bounds)
            }
            val backs = nodes.filter { it.label() in setOf("返回", "返回上一页", "Back") &&
                it.bounds.left < scope.bounds.left + scope.bounds.width() / 4 &&
                it.bounds.bottom < input.bounds.top && it.bounds.top < scope.bounds.top + scope.bounds.height() / 4 }
            val settings = nodes.filter { (it.label() in setOf("聊天设置", "聊天详情", "会话设置", "群聊设置", "群聊详情") ||
                (hasVoiceComposer && it.label() == "更多")) &&
                it.bounds.left > scope.bounds.left + scope.bounds.width() * 2 / 3 && it.bounds.bottom < input.bounds.top }
            if (backs.isEmpty() || settings.isEmpty()) continue
            // 同一个按钮可能同时暴露父、子无障碍节点；完全重合的语义锚点合并。
            val back = backs.distinctBy { it.bounds }.singleOrNull() ?: return skipped(DouyinPageStatus.AMBIGUOUS_CHAT)
            val setting = settings.distinctBy { it.bounds }.singleOrNull() ?: return skipped(DouyinPageStatus.AMBIGUOUS_CHAT)
            if (!back.bounds.overlapsVertically(setting.bounds)) return skipped(DouyinPageStatus.INVALID_BOUNDS)
            val headerBottom = maxOf(back.bounds.bottom, setting.bounds.bottom)
            val titles = nodes.filter {
                it.className.orEmpty().endsWith("TextView") && it.label().isNotBlank() &&
                    it.bounds.left >= back.bounds.right && it.bounds.right <= setting.bounds.left &&
                    it.bounds.top >= minOf(back.bounds.top, setting.bounds.top) && it.bounds.bottom <= headerBottom
            }.distinctBy { it.bounds to it.label() }
            val firstTitle = titles.minByOrNull { it.bounds.top } ?: return skipped(DouyinPageStatus.AMBIGUOUS_TITLE)
            val title = titles.filter { it.bounds.overlapsVertically(firstTitle.bounds) }.singleOrNull()
                ?: return skipped(DouyinPageStatus.AMBIGUOUS_TITLE)
            if (Regex("^(消息|消息列表|私信|评论|直播|搜索)([（(].*[）)])?$").matches(title.label())) {
                return skipped(DouyinPageStatus.NON_CHAT_PAGE)
            }
            val lists = nodes.filter {
                val clazz = it.className.orEmpty()
                (clazz.endsWith("RecyclerView") || clazz.endsWith("ListView")) &&
                    it.bounds.top >= headerBottom && it.bounds.top - headerBottom <= scope.bounds.height() / 10 &&
                    it.bounds.bottom <= input.bounds.top && input.bounds.top - it.bounds.bottom <= scope.bounds.height() / 5 &&
                    it.bounds.height() >= scope.bounds.height() / 5 && it.bounds.width() >= scope.bounds.width() * 2 / 3
            }.distinctBy { it.bounds }
            val body = lists.singleOrNull() ?: return skipped(DouyinPageStatus.MISSING_MESSAGE_LIST)
            val bounds = IntRect(body.bounds.left, body.bounds.top, body.bounds.right, input.bounds.top)
            if (!root.bounds.contains(scope.bounds) || !scope.bounds.contains(input.bounds) ||
                !scope.bounds.contains(bounds) || input.bounds.height() <= 0 || input.bounds.width() <= 0 ||
                bounds.height() <= 0 || input.bounds.top < scope.bounds.top + scope.bounds.height() / 3) {
                return skipped(DouyinPageStatus.INVALID_BOUNDS)
            }
            return DouyinParseOutcome(viewport(title, input, bounds), DouyinPageStatus.MATCHED_STRUCTURE)
        }
        return skipped(DouyinPageStatus.MISSING_CHAT_HEADER)
    }

    private fun skipped(status: DouyinPageStatus) = DouyinParseOutcome(ParseResult.Skip(
        if (status == DouyinPageStatus.AMBIGUOUS_TITLE || status == DouyinPageStatus.AMBIGUOUS_CHAT)
            SkipReason.AMBIGUOUS_CONVERSATION else SkipReason.UNSUPPORTED_PAGE,
    ), status)

    private fun UiNodeSnapshot.label() = text?.trim().takeUnless { it.isNullOrEmpty() }
        ?: contentDescription.orEmpty().trim()
    private fun IntRect.width() = right - left
    private fun IntRect.height() = bottom - top
    private fun IntRect.overlapsVertically(other: IntRect) = top < other.bottom && bottom > other.top
    private fun IntRect.contains(other: IntRect) = other.left >= left && other.right <= right &&
        other.top >= top && other.bottom <= bottom

    private fun UiNodeSnapshot.hasId(id: String) = viewId == "$packageName:id/$id"
    private fun UiNodeSnapshot.flatten(): List<UiNodeSnapshot> = listOf(this) + children.flatMap { it.flatten() }
}
