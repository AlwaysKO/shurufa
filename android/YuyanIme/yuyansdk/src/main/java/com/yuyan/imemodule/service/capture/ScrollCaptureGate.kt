package com.yuyan.imemodule.service.capture

import com.yuyan.imemodule.data.capture.ui.CancellableTask
import com.yuyan.imemodule.data.capture.ui.DebounceScheduler

/** 仅明确滚动事件重置停止计时；普通内容更新不能延长或提前解除滚动状态。 */
internal class ScrollCaptureGate(
    private val scheduler: DebounceScheduler,
    private val onStopped: (ScreenshotScope) -> Unit,
) {
    private var active: ScreenshotScope? = null
    private var revision = 0L
    private var pending: CancellableTask? = null

    @Synchronized fun scrolled(scope: ScreenshotScope) {
        pending?.cancel()
        active = scope
        val token = ++revision
        pending = scheduler.schedule(300) {
            synchronized(this) {
                if (revision != token || active != scope) return@synchronized
                active = null
                pending = null
                onStopped(scope)
            }
        }
    }
    @Synchronized fun blocks(scope: ScreenshotScope): Boolean = active == scope
    @Synchronized fun isScrolling(): Boolean = active != null
    @Synchronized fun clear() {
        revision++
        pending?.cancel()
        pending = null
        active = null
    }
}
