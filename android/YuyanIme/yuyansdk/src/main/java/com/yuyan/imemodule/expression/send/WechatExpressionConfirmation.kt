package com.yuyan.imemodule.expression.send

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.ExtractedTextRequest

/** 只观察本次微信图片确认，原编辑节点不可验证时保留文字。正文只暂存在内存。 */
internal object WechatExpressionConfirmation {
    private const val WECHAT = "com.tencent.mm"
    private var service: AccessibilityService? = null
    private val state = ExpressionConfirmation()
    private val handler = Handler(Looper.getMainLooper())
    private var generation = 0L
    private var editor: AccessibilityNodeInfo? = null
    private var originalText: String? = null

    fun connect(value: AccessibilityService) { service = value }
    fun disconnect(value: AccessibilityService) {
        if (service === value) { cancel(); service = null }
    }

    fun arm(connection: InputConnection): Long? = runCatching { capture(connection) }.getOrElse {
        trace("capture_error"); cancel(); null
    }

    private fun capture(connection: InputConnection): Long? {
        cancel()
        val observer = service ?: return null.also { trace("no_service") }
        val text = runCatching {
            val extracted = connection.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = 16_384 }, 0)
                ?: return@runCatching null
            extracted.text?.toString()?.takeIf {
                extracted.startOffset == 0 && extracted.partialStartOffset == -1 && extracted.partialEndOffset == -1 &&
                    it.isNotEmpty() && it.length < 16_384
            }
        }.getOrNull() ?: return null.also { trace("no_snapshot") }
        val root = observer.rootInActiveWindow ?: return null.also { trace("no_root") }
        try {
            if (root.packageName?.toString() != WECHAT) return null.also { trace("other_root") }
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null.also { trace("no_focused_editor") }
            if (!focused.isEditable || focused.isPassword || focused.packageName?.toString() != WECHAT ||
                focused.text?.toString() != text) {
                focused.recycle()
                trace("editor_mismatch")
                return null
            }
            editor = focused
            originalText = text
            state.begin(root.windowId, SystemClock.uptimeMillis())
            val token = generation
            trace("armed")
            handler.postDelayed({ if (generation == token) cancel() }, 120_001)
            return token
        } finally { root.recycle() }
    }

    fun cancel(token: Long? = null) {
        if (token != null && token != generation) return
        generation++
        state.cancel()
        editor?.recycle()
        editor = null
        originalText = null
    }

    fun event(event: AccessibilityEvent) {
        runCatching { observe(event) }.onFailure { trace("event_error"); cancel() }
    }

    private fun observe(event: AccessibilityEvent) {
        if (event.packageName?.toString() == WECHAT &&
            (event.text.any { it.toString() == "发送到当前聊天" } ||
                (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED && event.text.any { it.toString() in listOf("发送", "取消") }))) {
            trace("event_${event.eventType}_pending_${editor != null}")
        }
        if (editor == null) return
        val now = SystemClock.uptimeMillis()
        if (!state.active(now)) { cancel(); return }
        val pkg = event.packageName?.toString()
        if (pkg != WECHAT) {
            // 通知覆盖不构成发送确认；切到其他应用立即撤销，不跨应用留待清空。
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != service?.packageName) cancel()
            return
        }
        if (event.eventTime > now || event.eventTime < now - 120_000) return
        val texts = event.text.map(CharSequence::toString)
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (event.className?.startsWith("com.tencent.mm.ui.widget.dialog.") == true &&
                    texts.containsAll(listOf("发送到当前聊天", "取消", "发送"))) {
                    state.dialog(event.windowId, event.eventTime)
                    trace("dialog")
                } else if (!state.acceptsWindow(event.windowId)) cancel()
                else if (event.windowId == state.originWindow) {
                    // Android 可能先报告窗口恢复再递送同次点击；只留短事件乱序窗口，绝不据此确认。
                    state.returned(event.eventTime)
                    val token = generation
                    handler.postDelayed({ if (generation == token && !state.active(SystemClock.uptimeMillis())) cancel() }, 301)
                }
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                if (texts == listOf("发送") || texts == listOf("取消")) {
                    state.click(event.windowId, texts.single() == "发送", event.eventTime)
                    trace(if (texts.single() == "发送") "send_click" else "cancel_click")
                    if (!state.active(now)) { cancel(); return }
                } else { cancel(); return }
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                // 一旦原窗口有文字编辑就失效，即使稍后恢复成相同原文也不复用旧确认。
                if (event.windowId == state.originWindow) { cancel(); return }
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> { cancel(); return }
        }
        if (state.isConfirmed(now)) {
            val token = generation
            tryClear(token)
            for (delay in listOf(100L, 300L, 800L, 1500L)) handler.postDelayed({ runCatching { tryClear(token) }.onFailure { cancel(token) } }, delay)
        }
    }

    private fun trace(stage: String) {
        if (com.yuyan.imemodule.BuildConfig.DEBUG) runCatching { android.util.Log.d("ExpressionConfirm", stage) }
    }

    private fun tryClear(token: Long) {
        if (generation != token || !state.isConfirmed(SystemClock.uptimeMillis())) return
        val node = editor ?: return
        val root = service?.rootInActiveWindow ?: return
        try {
            if (root.packageName?.toString() != WECHAT || !state.acceptsWindow(root.windowId)) { cancel(); return }
            // 等确认框退出后操作原节点，不重新查找可能属于其他会话的同名输入框。
            if (root.windowId != state.originWindow) return
            if (!node.refresh() || node.windowId != state.originWindow || !node.isEditable || node.isPassword ||
                node.packageName?.toString() != WECHAT || node.text?.toString() != originalText) { cancel(); return }
            val clear = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "") }
            // 先消费凭据，防止 ACTION_SET_TEXT 产生重入事件造成重复操作。
            editor = null
            cancel()
            try { trace("clear_${node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clear)}") } finally { node.recycle() }
        } finally { root.recycle() }
    }
}
