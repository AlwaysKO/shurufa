package com.yuyan.imemodule.service.capture

import android.view.accessibility.AccessibilityEvent
import com.yuyan.imemodule.data.capture.CapturePersistResult

internal data class ScreenshotScope(val window: Int, val generation: Long)

internal class ScreenshotUpdatePolicy {
    private var confirmed: ScreenshotScope? = null
    private var pendingScrollResume: ScreenshotScope? = null
    @Synchronized fun allowScrollResume(scope: ScreenshotScope) { pendingScrollResume = scope }
    @Synchronized fun canResumeScroll(scope: ScreenshotScope): Boolean =
        confirmed == scope || pendingScrollResume == scope
    @Synchronized fun rejectScrollResume(scope: ScreenshotScope) {
        if (pendingScrollResume == scope) pendingScrollResume = null
        if (confirmed == scope) { confirmed = null; lastSaved = null }
    }
    private data class SavedContent(val identity: String, val title: String, val body: String)
    private var lastSaved: SavedContent? = null
    @Synchronized fun hasSavedContent(scope: ScreenshotScope): Boolean = confirmed == scope && lastSaved != null
    private fun content(identity: String, title: String?, body: String?): SavedContent? =
        if (identity.isBlank() || title.isNullOrBlank() || body.isNullOrBlank()) null else SavedContent(identity, title, body)
    @Synchronized fun isSavedContent(scope: ScreenshotScope, identity: String, titleHash: String?, bodyHash: String?): Boolean =
        confirmed == scope && content(identity, titleHash, bodyHash)?.let { it == lastSaved } == true
    @Synchronized fun recordSavedContent(scope: ScreenshotScope, identity: String, titleHash: String?, bodyHash: String?,
        result: CapturePersistResult, sameFrameConfirmed: Boolean) {
        if (confirmed != scope || result == CapturePersistResult.FAILED) return
        // 确认重放持有首帧图像，却可能携带第二帧标题；不能拼接成不存在的保存记录。
        lastSaved = if (sameFrameConfirmed) content(identity, titleHash, bodyHash) else null
    }
    fun observeTitle(window: Int, generation: Long, status: String) = Unit
    @Synchronized fun confirm(window: Int, generation: Long) {
        val scope = ScreenshotScope(window, generation)
        if (scope != confirmed) lastSaved = null
        confirmed = scope
    }
    @Synchronized fun clear() { confirmed = null; lastSaved = null; pendingScrollResume = null }
    @Synchronized fun accepts(window: Int, generation: Long, eventType: Int): Boolean =
        confirmed == ScreenshotScope(window, generation) && eventType in setOf(
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, AccessibilityEvent.TYPE_VIEW_SCROLLED)
}

/** 一次物理处理期间只保留最后一个检查请求，不丢掉进行中到达的末次变化。 */
internal class ScreenshotRequestGate {
    private var running: ScreenshotScope? = null
    private var dirty: ScreenshotScope? = null
    private var pending: ScreenshotScope? = null
    @Synchronized fun offer(scope: ScreenshotScope): Boolean {
        if (running != null) { pending = scope; return false }
        running = scope
        return true
    }
    @Synchronized fun complete(confirmed: ScreenshotScope? = null): ScreenshotScope? {
        running = null
        val next = pending ?: dirty?.takeIf { it == confirmed }
        pending = null
        dirty = null
        return next
    }
    /** 初次身份未确认时只记变化，不能直接放行未知页面。 */
    @Synchronized fun markChanged(scope: ScreenshotScope): Boolean {
        if (running != scope) return false
        dirty = scope
        return true
    }
    @Synchronized fun clearPending() { pending = null; dirty = null }
}
