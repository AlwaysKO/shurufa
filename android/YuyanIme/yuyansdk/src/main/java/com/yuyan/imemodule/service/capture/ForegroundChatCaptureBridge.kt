package com.yuyan.imemodule.service.capture

import android.view.accessibility.AccessibilityEvent
import com.yuyan.imemodule.data.capture.ui.CancellableTask

enum class ForegroundChatCaptureReason { PROBE, SEND }

data class ForegroundChatCaptureRequest(
    val packageName: String,
    val requestedAtMillis: Long,
    val reason: ForegroundChatCaptureReason = ForegroundChatCaptureReason.PROBE,
)

internal val FOREGROUND_CHAT_CAPTURE_PACKAGES = setOf(
    "com.tencent.mm",
    "com.tencent.mobileqq",
    "com.ss.android.ugc.aweme",
)

internal val ACCESSIBILITY_CHAT_EVENT_PACKAGES = setOf(
    "com.tencent.mm",
    "com.tencent.mobileqq",
    "com.ss.android.ugc.aweme",
)

internal fun isForegroundChatCapturePackage(packageName: String?): Boolean =
    packageName in FOREGROUND_CHAT_CAPTURE_PACKAGES

internal fun isExplicitChatSendAction(text: List<String>, description: String?, className: String?): Boolean {
    if (className.orEmpty().endsWith("EditText")) return false
    val labels = (text + listOfNotNull(description)).map(String::trim).filter(String::isNotEmpty)
    return labels.isNotEmpty() && labels.all { it == "发送" || it.equals("send", ignoreCase = true) }
}

internal fun shouldCaptureForegroundChatEvent(
    eventType: Int,
    className: String?,
    visibleText: String? = null,
): Boolean = when (eventType) {
    AccessibilityEvent.TYPE_VIEW_SCROLLED -> true
    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> !className.orEmpty().endsWith("EditText")
    AccessibilityEvent.TYPE_VIEW_CLICKED -> visibleText.orEmpty().isWeChatCaptureAction()
    else -> true
}

internal fun shouldCaptureEmptyTreeWeChatOpen(
    eventType: Int,
    className: String?,
    visibleText: String?,
    activeTreeUsable: Boolean,
    sourceTreeUsable: Boolean,
): Boolean {
    if (eventType != AccessibilityEvent.TYPE_VIEW_CLICKED || activeTreeUsable || sourceTreeUsable) return false
    val text = visibleText.orEmpty().trim()
    if (className.isNullOrBlank() && !text.contains("转文字")) return false
    return text.isWeChatCaptureAction() || isConversationRowClick(text)
}

internal fun emptyTreeWeChatCaptureDelays(visibleText: String?): List<Long> = when {
    visibleText.orEmpty().trim().contains("转文字") -> listOf(1_500L, 6_000L)
    visibleText.orEmpty().isWeChatCaptureAction() ||
        isConversationRowClick(visibleText.orEmpty().trim()) -> listOf(0L, 350L, 900L, 2_200L, 4_400L)
    else -> emptyList()
}

private fun String.isWeChatCaptureAction(): Boolean = contains("发送") || contains("转文字")

private val EMPTY_TREE_CONVERSATION_ROW_TIME = Regex(
    "^(?:\\d{1,2}:\\d{2}|昨天|星期[一二三四五六日天]|\\d{1,2}月\\d{1,2}日)$",
)

object ForegroundChatCaptureBridge {
    private var handler: ((ForegroundChatCaptureRequest) -> Unit)? = null

    @Synchronized
    fun connect(callback: (ForegroundChatCaptureRequest) -> Unit): CancellableTask {
        handler = callback
        return CancellableTask {
            synchronized(this) {
                if (handler === callback) handler = null
            }
        }
    }

    @Synchronized
    fun request(packageName: String?, requestedAtMillis: Long = System.currentTimeMillis(), reason: ForegroundChatCaptureReason = ForegroundChatCaptureReason.PROBE) {
        if (!isForegroundChatCapturePackage(packageName)) return
        handler?.invoke(ForegroundChatCaptureRequest(packageName.orEmpty(), requestedAtMillis, reason))
    }
}

/** 列表节点可能把联系人、时间和摘要放在同一个事件文本中；不能只接受纯时间。 */
private fun isConversationRowClick(text: String): Boolean = text.split(Regex("\\s+")).any { EMPTY_TREE_CONVERSATION_ROW_TIME.matches(it) }

/** 只重新检查页面树，真正截图仍须适配器确认聊天页；不扩大至其他App或非聊天页。 */
internal fun foregroundChatProbeDelays(eventType: Int, className: String?): List<Long> =
    if ((eventType == AccessibilityEvent.TYPE_VIEW_CLICKED && !className.orEmpty().endsWith("EditText")) ||
        eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) listOf(0L, 350L, 900L, 2_200L, 4_400L) else emptyList()

internal fun hasReadableChatContent(root: com.yuyan.imemodule.data.capture.ui.UiNodeSnapshot?): Boolean =
    root != null && (!root.text.isNullOrBlank() || !root.contentDescription.isNullOrBlank() || root.children.any { hasReadableChatContent(it) })

/** 发送后的视口动画只短时合并，不能用输入空闲时间代替正文渲染完成。 */
internal class SendRenderWait(
    val packageName: String,
    val generation: Long,
    val startedAtUptime: Long = 0L,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val startedAt = clock()
    private var changedAt: Long? = null
    private data class TailPosition(val source: String, val from: Int, val to: Int, val count: Int)
    private var tail: Pair<TailPosition, Long>? = null
    private var stableTailAt: Long? = null

    @Synchronized fun changed() {
        changedAt = clock()
        // 微信常先发content再发scroll；仅撤销当前许可，待新scroll重新核对位置。
        stableTailAt = null
    }

    /** 位置重复仅缩短本次发送的布局等待，不证明消息新增，也不替代内容去重。 */
    @Synchronized fun scrolled(source: String, from: Int, to: Int, count: Int) {
        val now = clock()
        changedAt = now
        stableTailAt = null
        if (source.isBlank() || from < 0 || from > to || count <= 0 || to != count - 1) {
            tail = null
            return
        }
        val position = TailPosition(source, from, to, count)
        val previous = tail
        if (previous?.first == position && now - previous.second >= 60) stableTailAt = now
        tail = position to now
    }

    private fun readyAt(): Long = stableTailAt?.let { maxOf(startedAt + 300, it) }
        ?: changedAt?.let { maxOf(startedAt + 300, it + 90) } ?: (startedAt + 350)
    @Synchronized fun isSettled(): Boolean = readyAt().let { it <= startedAt + 500 && clock() >= it }
    @Synchronized fun remainingMillis(): Long = (minOf(readyAt(), startedAt + 500) - clock()).coerceAtLeast(0)
    fun canRetry(): Boolean = clock() < startedAt + 500
}
