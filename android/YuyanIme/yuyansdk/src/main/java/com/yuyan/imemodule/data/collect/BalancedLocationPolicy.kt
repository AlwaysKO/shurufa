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
    private var confirmationRetryAt = 0L
    val isStationary: Boolean get() = samplingIntervalMs == STATIONARY_INTERVAL_MS

    fun observe(nowMs: Long, candidate: LocationCandidate, speedMps: Float?, elapsedMs: Long = nowMs): Boolean {
        // Reissued cache coordinates and zero speed cannot establish a real stay.
        if (candidate.knownCached) return false
        if (!jumpFilter.accept(nowMs, candidate, speedMps)) {
            if (jumpFilter.needsConfirmation) {
                if (confirmationStartedAt == null && samplingIntervalMs == STATIONARY_INTERVAL_MS && elapsedMs >= confirmationRetryAt) confirmationStartedAt = elapsedMs
                confirmationStartedAt?.let {
                    if (elapsedMs - it in 0 until CONFIRMATION_WINDOW_MS) samplingIntervalMs = CONFIRMATION_INTERVAL_MS
                    else endConfirmation(elapsedMs)
                }
            } else if (confirmationStartedAt != null) {
                confirmationStartedAt = null
                samplingIntervalMs = STATIONARY_INTERVAL_MS
            }
            return false
        }
        if (candidate.locationTimeMs <= previousTime) return false
        val wasConfirming = confirmationStartedAt != null
        confirmationStartedAt = null
        confirmationRetryAt = 0
        val oldAnchor = anchor
        val gap = candidate.locationTimeMs - previousTime
        previousTime = candidate.locationTimeMs
        val moving = speedMps?.let { it.isFinite() && it > 0.3f } == true ||
            (oldAnchor != null && distance(oldAnchor, candidate) > LocationUploadPolicy.movementThreshold(oldAnchor, candidate))
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
        confirmationStartedAt = elapsedMs
        samplingIntervalMs = CONFIRMATION_INTERVAL_MS
        return true
    }

    fun confirmationDelayMs(elapsedMs: Long): Long? = confirmationStartedAt
        ?.takeIf { samplingIntervalMs == CONFIRMATION_INTERVAL_MS }
        ?.let { (CONFIRMATION_WINDOW_MS - (elapsedMs - it)).coerceIn(0, CONFIRMATION_WINDOW_MS) }

    /** Expiry changes sampling only; a missing fix cannot confirm movement or create a report. */
    fun endConfirmation(elapsedMs: Long? = null) {
        confirmationStartedAt?.let {
            samplingIntervalMs = STATIONARY_INTERVAL_MS
            confirmationRetryAt = maxOf(elapsedMs ?: (it + CONFIRMATION_WINDOW_MS), it + CONFIRMATION_WINDOW_MS) + CONFIRMATION_COOLDOWN_MS
            confirmationStartedAt = null
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
        private const val CONFIRMATION_COOLDOWN_MS = 60_000L
    }
}
