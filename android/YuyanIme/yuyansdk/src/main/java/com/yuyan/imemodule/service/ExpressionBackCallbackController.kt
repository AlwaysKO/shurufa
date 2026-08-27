package com.yuyan.imemodule.service

class ExpressionBackCallbackController(
    private val register: () -> Unit,
    private val unregister: () -> Unit,
) {
    private var enabled = false

    fun setEnabled(enabled: Boolean) {
        if (this.enabled == enabled) return
        this.enabled = enabled
        if (enabled) register() else unregister()
    }

    fun clear() {
        setEnabled(false)
    }
}
