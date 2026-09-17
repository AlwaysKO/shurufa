package com.yuyan.imemodule.service.capture

import android.view.accessibility.AccessibilityEvent
import com.yuyan.imemodule.data.capture.ui.CancellableTask

data class ForegroundChatCaptureRequest(
    val packageName: String,
    val requestedAtMillis: Long,
)

internal val FOREGROUND_CHAT_CAPTURE_PACKAGES = setOf(
    "com.tencent.mm",
    "com.ss.android.ugc.aweme",
)

internal val ACCESSIBILITY_CHAT_EVENT_PACKAGES = setOf(
    "com.tencent.mm",
    "com.tencent.mobileqq",
    "com.ss.android.ugc.aweme",
)

internal fun isForegroundChatCapturePackage(packageName: String?): Boolean =
    packageName in FOREGROUND_CHAT_CAPTURE_PACKAGES

internal fun shouldCaptureForegroundChatEvent(
    eventType: Int,
    className: String?,
    visibleText: String? = null,
): Boolean = when (eventType) {
    AccessibilityEvent.TYPE_VIEW_SCROLLED -> false
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
    return text.isWeChatCaptureAction() || EMPTY_TREE_CONVERSATION_ROW_TIME.matches(text)
}

internal fun emptyTreeWeChatCaptureDelays(visibleText: String?): List<Long> = when {
    visibleText.orEmpty().trim().contains("转文字") -> listOf(1_500L, 6_000L)
    visibleText.orEmpty().isWeChatCaptureAction() ||
        EMPTY_TREE_CONVERSATION_ROW_TIME.matches(visibleText.orEmpty().trim()) -> listOf(700L)
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
    fun request(packageName: String?, requestedAtMillis: Long = System.currentTimeMillis()) {
        if (!isForegroundChatCapturePackage(packageName)) return
        handler?.invoke(ForegroundChatCaptureRequest(packageName.orEmpty(), requestedAtMillis))
    }
}
