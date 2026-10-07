package com.yuyan.imemodule.service.capture

/** 每个窗口代次最多三次、至少间隔两秒；仅允许图像确认尝试，绝不允许直接保存。 */
internal class EmptyTreeCandidateProbe(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private var current: ScreenshotScope? = null
    private var attempts = 0
    private var nextAt = 0L
    @Synchronized fun canAttempt(scope: ScreenshotScope): Boolean =
        current != scope || (attempts < 3 && now() >= nextAt)
    @Synchronized fun consume(scope: ScreenshotScope): Boolean {
        if (!canAttempt(scope)) return false
        if (current != scope) { current = scope; attempts = 0 }
        attempts++
        nextAt = now() + 2_000
        return true
    }
    /** 跟随实际首帧时间恢复，避免输入避让后开页探测已全部过期。 */
    @Synchronized fun retryDelayMillis(scope: ScreenshotScope): Long? =
        if (current == scope && attempts in 1..2) (nextAt - now()).coerceAtLeast(0) else null
    @Synchronized fun clear() { current = null; attempts = 0; nextAt = 0 }
}
