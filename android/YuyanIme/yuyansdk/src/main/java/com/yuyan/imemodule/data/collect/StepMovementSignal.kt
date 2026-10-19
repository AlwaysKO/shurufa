package com.yuyan.imemodule.data.collect

/** Session-local step evidence; never counts or persists a user's daily steps. */
internal class StepMovementSignal(private val armedAtNanos: Long) {
    private var last = Long.MIN_VALUE
    private var first: Long? = null
    fun observe(timestamp: Long, now: Long, value: Float): Boolean {
        if (!value.isFinite() || value <= 0 || timestamp <= armedAtNanos || timestamp <= last ||
            timestamp > now || now - timestamp > 10_000_000_000L) return false
        last = timestamp
        val previous = first
        first = timestamp
        return previous != null && timestamp - previous <= 15_000_000_000L
    }
}
