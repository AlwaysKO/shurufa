package com.yuyan.imemodule.data.collect

import kotlin.math.*

/** Sampling state is session-local: absence of observations is never evidence of a stay. */
internal class BalancedLocationPolicy {
    private var samplingIntervalMs = MOVING_INTERVAL_MS
    private var validatedWifi = false

    // Geographical state remains independent of transport. Off Wi-Fi even a stay
    // requests minute samples; the bounded 30-second movement confirmation remains.
    val intervalMs: Long
        get() = if (validatedWifi) samplingIntervalMs else minOf(samplingIntervalMs, NON_WIFI_STATIONARY_INTERVAL_MS)

    fun updateNetwork(wifi: Boolean) { validatedWifi = wifi }
    private var anchor: LocationCandidate? = null
    private var previousTime = 0L
    private var observations = 0
    private val jumpFilter = LocationJumpFilter()
    private var confirmationStartedAt: Long? = null
    private var sensorProbe = false
    private var confirmationRetryAt = 0L
    private var lastObservationElapsedMs = 0L
    private var freshStayStart: Long? = null
    private var freshStayObservations = 0
    private var unconfirmedSinceElapsedMs = 0L
    var hasUnconfirmedMotion = false
        private set
    // This is the low-power sampling state; an unresolved probe is not proof of a stay.
    val isStationary: Boolean get() = samplingIntervalMs == STATIONARY_INTERVAL_MS

    fun observe(nowMs: Long, candidate: LocationCandidate, speedMps: Float?, elapsedMs: Long = nowMs,
        receivedElapsedMs: Long = elapsedMs): Boolean {
        // Reissued cache coordinates and zero speed cannot establish a real stay.
        if (candidate.knownCached) return false
        if (!jumpFilter.accept(nowMs, candidate, speedMps)) {
            if (jumpFilter.needsConfirmation) {
                freshStayStart = null
                freshStayObservations = 0
                if (confirmationStartedAt == null && isStationary && elapsedMs >= confirmationRetryAt) {
                    startProbe(elapsedMs, hasUnconfirmedMotion)
                }
                confirmationStartedAt?.let {
                    if (elapsedMs - it in 0 until CONFIRMATION_WINDOW_MS) samplingIntervalMs = CONFIRMATION_INTERVAL_MS
                    else endConfirmation(elapsedMs)
                }
            } else if (confirmationStartedAt != null && !sensorProbe) {
                confirmationStartedAt = null
                samplingIntervalMs = STATIONARY_INTERVAL_MS
            }
            return false
        }
        if (candidate.locationTimeMs <= previousTime) return false
        // Stationary observations count too; the upload timestamp is intentionally
        // irrelevant. Selection/IO waking late must not make an old fix look fresh.
        val observedElapsed = if (candidate.elapsedRealtimeNanos > 0) candidate.elapsedRealtimeNanos / 1_000_000
            else receivedElapsedMs - (nowMs - candidate.locationTimeMs).coerceAtLeast(0L)
        if (observedElapsed in 0L..minOf(receivedElapsedMs, elapsedMs))
            lastObservationElapsedMs = maxOf(lastObservationElapsedMs, observedElapsed)
        val wasConfirming = confirmationStartedAt != null
        val oldAnchor = anchor
        val gap = candidate.locationTimeMs - previousTime
        previousTime = candidate.locationTimeMs
        val moving = speedMps?.let { it.isFinite() && it > 0.3f } == true ||
            (oldAnchor != null && distance(oldAnchor, candidate) > LocationUploadPolicy.movementThreshold(oldAnchor, candidate))
        // Movement hardware started a cold-start window. An inconclusive fix
        // cannot borrow the observations collected before that event to end it.
        if (hasUnconfirmedMotion && !moving) {
            // Delayed pre-motion fixes and fixes without an observation clock cannot
            // prove a new stay after the trigger. They still follow normal report filters.
            val postMotionFix = candidate.elapsedRealtimeNanos > 0 &&
                candidate.elapsedRealtimeNanos / 1_000_000 in unconfirmedSinceElapsedMs..elapsedMs
            if (postMotionFix) {
                if (freshStayStart == null || gap > 600_000) {
                    freshStayStart = candidate.elapsedRealtimeNanos / 1_000_000
                    freshStayObservations = 0
                }
                freshStayObservations++
            }
            if (postMotionFix && freshStayObservations >= 4 &&
                candidate.elapsedRealtimeNanos / 1_000_000 - requireNotNull(freshStayStart) >= 120_000) {
                clearUnconfirmedMotion()
                confirmationStartedAt = null
                sensorProbe = false
                samplingIntervalMs = STATIONARY_INTERVAL_MS
                anchor = candidate
                observations = 1
                return true
            }
            if (confirmationDelayMs(elapsedMs) == 0L) endConfirmation(elapsedMs)
            return true
        }
        clearUnconfirmedMotion()
        confirmationStartedAt = null
        sensorProbe = false
        if (oldAnchor == null || moving || gap > 600_000 ||
            (!wasConfirming && samplingIntervalMs == MOVING_INTERVAL_MS && gap > 90_000)) {
            anchor = candidate
            observations = 1
            samplingIntervalMs = MOVING_INTERVAL_MS
            return true
        }
        observations++
        if (observations >= 4 && candidate.locationTimeMs - oldAnchor.locationTimeMs >= 120_000) {
            samplingIntervalMs = STATIONARY_INTERVAL_MS
        }
        return true
    }

    fun motionProbeDelayMs(elapsedMs: Long): Long = (confirmationRetryAt - elapsedMs).coerceAtLeast(0L)

    /** A sensor event asks for fresh evidence; it never confirms a location or movement. */
    fun beginMotionProbe(elapsedMs: Long): Boolean {
        if (!isStationary || motionProbeDelayMs(elapsedMs) > 0) return false
        startProbe(elapsedMs, true)
        return true
    }

    private fun startProbe(elapsedMs: Long, uncertain: Boolean) {
        if (uncertain) startUnconfirmedMotion(elapsedMs)
        confirmationStartedAt = elapsedMs
        confirmationRetryAt = elapsedMs + OBSERVATION_TIMEOUT_MS
        sensorProbe = uncertain
        samplingIntervalMs = CONFIRMATION_INTERVAL_MS
    }

    fun confirmationDelayMs(elapsedMs: Long): Long? = confirmationStartedAt
        ?.takeIf { samplingIntervalMs == CONFIRMATION_INTERVAL_MS }
        ?.let { (CONFIRMATION_WINDOW_MS - (elapsedMs - it)).coerceIn(0, CONFIRMATION_WINDOW_MS) }

    fun recoveryDelayMs(elapsedMs: Long): Long? = if (isStationary)
        (maxOf(lastObservationElapsedMs + OBSERVATION_TIMEOUT_MS, confirmationRetryAt) - elapsedMs).coerceAtLeast(0L)
        else null

    fun beginRecovery(elapsedMs: Long): Boolean {
        if (!isStationary || recoveryDelayMs(elapsedMs) != 0L) return false
        startProbe(elapsedMs, true)
        return true
    }

    private fun startUnconfirmedMotion(elapsedMs: Long) {
        hasUnconfirmedMotion = true
        unconfirmedSinceElapsedMs = elapsedMs
        freshStayStart = null
        freshStayObservations = 0
    }

    private fun clearUnconfirmedMotion() {
        hasUnconfirmedMotion = false
        freshStayStart = null
        freshStayObservations = 0
    }

    /** Expiry changes sampling only; a missing fix cannot confirm movement or create a report. */
    fun endConfirmation(elapsedMs: Long? = null) {
        confirmationStartedAt?.let {
            if (elapsedMs != null && elapsedMs < it + CONFIRMATION_WINDOW_MS) return
            if (!hasUnconfirmedMotion) startUnconfirmedMotion(it)
            samplingIntervalMs = STATIONARY_INTERVAL_MS
            confirmationStartedAt = null
            sensorProbe = false
        }
    }

    private fun distance(a: LocationCandidate, b: LocationCandidate): Double {
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.latitude)) *
            cos(Math.toRadians(b.latitude)) * sin(dLon / 2).pow(2)
        return 12_742_000 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    companion object {
        const val MOVING_INTERVAL_MS = 60_000L
        private const val NON_WIFI_STATIONARY_INTERVAL_MS = 60_000L
        private const val CONFIRMATION_INTERVAL_MS = 30_000L
        const val STATIONARY_INTERVAL_MS = 300_000L
        private const val CONFIRMATION_WINDOW_MS = 120_000L
        private const val OBSERVATION_TIMEOUT_MS = 600_000L
    }
}
