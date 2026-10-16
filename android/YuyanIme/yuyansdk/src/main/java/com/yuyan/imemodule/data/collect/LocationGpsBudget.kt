package com.yuyan.imemodule.data.collect

/** Monotonic GPS lease. Neither registration nor repeated/cache fixes buy more time. */
internal class LocationGpsBudget {
    var deadline: Long? = null
        private set
    private var retryAt: Long? = null
    private var failures = 0
    private var lastFixTime = Long.MIN_VALUE
    private var lastFixElapsed = 0L
    private var earlyMotionUsed = false

    fun observe(candidate: LocationCandidate, receivedElapsedMs: Long) {
        if (candidate.knownCached || candidate.provider != "gps") return
        if (candidate.locationTimeMs <= lastFixTime ||
            (lastFixElapsed > 0 && candidate.elapsedRealtimeNanos > 0 && candidate.elapsedRealtimeNanos <= lastFixElapsed)) return
        lastFixTime = candidate.locationTimeMs
        lastFixElapsed = candidate.elapsedRealtimeNanos
        failures = 0
        earlyMotionUsed = false
        // Allow the existing bounded selection window, never an unbounded amount
        // of processing delay. The native request itself remains at most 120s.
        deadline = receivedElapsedMs + WINDOW_MS + LocationFixSelection.WINDOW_MS
        retryAt = null
    }

    fun update(elapsedMs: Long, stationary: Boolean, trustedMotion: Boolean = false) {
        if (stationary) {
            deadline = null
            retryAt = null
            failures = 0
            return
        }
        if (deadline?.let { elapsedMs >= it } == true) {
            deadline = null
            retryAt = elapsedMs + BACKOFF[minOf(failures, BACKOFF.lastIndex)]
            failures++
            return
        }
        val early = deadline == null && retryAt != null && elapsedMs < requireNotNull(retryAt) && trustedMotion && !earlyMotionUsed
        if (early) earlyMotionUsed = true
        if (deadline == null && (early || retryAt == null || elapsedMs >= requireNotNull(retryAt))) {
            deadline = elapsedMs + WINDOW_MS
            retryAt = null
        }
    }

    fun nextAt(): Long? = deadline ?: retryAt
    fun retryDelay(elapsedMs: Long): Long? = retryAt?.let { (it - elapsedMs).coerceAtLeast(0L) }
    companion object {
        const val WINDOW_MS = 120_000L
        private val BACKOFF = longArrayOf(60_000L, 120_000L, 300_000L)
    }
}
