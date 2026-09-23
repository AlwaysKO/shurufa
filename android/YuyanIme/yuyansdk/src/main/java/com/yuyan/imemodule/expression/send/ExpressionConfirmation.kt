package com.yuyan.imemodule.expression.send

/** 一次图片交接的确认凭据。窗口消失本身从不产生发送确认。 */
internal class ExpressionConfirmation {
    var originWindow: Int = -1
        private set
    private var dialogWindow = -1
    private var started = -1L
    private var confirmedAt = -1L
    private var returnedAt = -1L

    fun begin(window: Int, now: Long) {
        cancel()
        originWindow = window
        started = now
    }
    fun dialog(window: Int, at: Long) {
        if (active(at) && window != originWindow && dialogWindow == -1) dialogWindow = window
    }
    fun click(window: Int, send: Boolean, at: Long) {
        if (!active(at) || window != dialogWindow || dialogWindow == -1) { cancel(); return }
        if (send) {
            if (confirmedAt == -1L) confirmedAt = at
        } else cancel()
    }
    fun returned(at: Long) {
        if (!active(at) || dialogWindow == -1) return
        if (returnedAt >= 0) { cancel(); return }
        returnedAt = at
    }
    fun active(now: Long): Boolean = started >= 0 && now in started..started + 120_000 &&
        (confirmedAt == -1L || now <= confirmedAt + 2_000) &&
        (returnedAt == -1L || confirmedAt >= 0 || now <= returnedAt + 300)
    fun isConfirmed(now: Long): Boolean = active(now) && confirmedAt >= 0
    fun acceptsWindow(window: Int): Boolean = window == originWindow || window == dialogWindow
    fun cancel() {
        originWindow = -1
        dialogWindow = -1
        started = -1L
        confirmedAt = -1L
        returnedAt = -1L
    }
}
