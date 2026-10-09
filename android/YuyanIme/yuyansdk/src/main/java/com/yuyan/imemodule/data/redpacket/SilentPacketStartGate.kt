package com.yuyan.imemodule.data.redpacket

/** Main-thread start gate. Callers supply milliseconds from the same monotonic clock. */
internal class SilentPacketStartGate(private val quietWindowMs: Long = 600) {
    private var deferredAt: Long? = null

    init {
        require(quietWindowMs >= 0)
    }

    fun defer(now: Long) {
        deferredAt = now
    }

    fun remaining(now: Long): Long {
        val start = deferredAt ?: return 0
        return maxOf(0, quietWindowMs - (now - start))
    }
}
