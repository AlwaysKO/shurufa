package com.yuyan.imemodule.service

class ExpressionBackCallbackController(
    private val register: () -> Unit,
    private val unregister: () -> Unit,
) {
    private var enabled = false
    private var dispatchingBack = false
    private var pendingDisable = false

    fun setEnabled(enabled: Boolean) {
        if (dispatchingBack && !enabled) {
            pendingDisable = true
            return
        }
        if (this.enabled == enabled) return
        this.enabled = enabled
        if (enabled) register() else unregister()
    }

    fun onBackInvoked(
        handleBack: () -> Boolean,
        fallback: () -> Unit,
        post: (() -> Unit) -> Unit,
    ) {
        dispatchingBack = true
        val handled = try {
            handleBack()
        } finally {
            dispatchingBack = false
        }
        if (!handled) {
            pendingDisable = false
            fallback()
            return
        }
        if (pendingDisable) {
            pendingDisable = false
            post { setEnabled(false) }
        }
    }

    fun clear() {
        setEnabled(false)
    }
}
