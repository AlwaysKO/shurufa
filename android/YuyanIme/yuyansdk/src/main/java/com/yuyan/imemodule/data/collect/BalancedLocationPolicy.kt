package com.yuyan.imemodule.data.collect

import kotlin.math.*

/** Sampling state is session-local: absence of observations is never evidence of a stay. */
internal class BalancedLocationPolicy {
    var intervalMs = MOVING_INTERVAL_MS
        private set
    private var anchor: LocationCandidate? = null
    private var previousTime = 0L
    private var observations = 0
    private val jumpFilter = LocationJumpFilter()
    private var confirmationStartedAt: Long? = null

    fun observe(nowMs: Long, candidate: LocationCandidate, speedMps: Float?, elapsedMs: Long = nowMs) {
        if (!jumpFilter.accept(nowMs, candidate, speedMps)) {
            if (jumpFilter.needsConfirmation) {
                if (confirmationStartedAt == null && intervalMs == STATIONARY_INTERVAL_MS) confirmationStartedAt = elapsedMs
                confirmationStartedAt?.let {
                    intervalMs = if (elapsedMs - it in 0 until CONFIRMATION_WINDOW_MS) MOVING_INTERVAL_MS else STATIONARY_INTERVAL_MS
                }
            } else if (confirmationStartedAt != null) {
                confirmationStartedAt = null
                intervalMs = STATIONARY_INTERVAL_MS
            }
            return
        }
        if (candidate.locationTimeMs <= previousTime) return
        val wasConfirming = confirmationStartedAt != null
        confirmationStartedAt = null
        val oldAnchor = anchor
        val gap = candidate.locationTimeMs - previousTime
        previousTime = candidate.locationTimeMs
        val moving = speedMps?.let { it.isFinite() && it > 1.5f } == true ||
            (oldAnchor != null && distance(oldAnchor, candidate) > max(50.0, oldAnchor.accuracyMeters + candidate.accuracyMeters.toDouble()))
        if (oldAnchor == null || moving || gap > 600_000 ||
            (!wasConfirming && intervalMs == MOVING_INTERVAL_MS && gap > 90_000)) {
            anchor = candidate
            observations = 1
            intervalMs = MOVING_INTERVAL_MS
            return
        }
        observations++
        if (observations >= 4 && candidate.locationTimeMs - oldAnchor.locationTimeMs >= 120_000) {
            intervalMs = STATIONARY_INTERVAL_MS
        }
    }

    fun confirmationDelayMs(elapsedMs: Long): Long? = confirmationStartedAt
        ?.takeIf { intervalMs == MOVING_INTERVAL_MS }
        ?.let { (CONFIRMATION_WINDOW_MS - (elapsedMs - it)).coerceIn(0, CONFIRMATION_WINDOW_MS) }

    /** Expiry changes sampling only; a missing fix cannot confirm movement or create a report. */
    fun endConfirmation() {
        if (confirmationStartedAt != null) intervalMs = STATIONARY_INTERVAL_MS
    }

    private fun distance(a: LocationCandidate, b: LocationCandidate): Double {
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.latitude)) *
            cos(Math.toRadians(b.latitude)) * sin(dLon / 2).pow(2)
        return 12_742_000 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    companion object {
        const val MOVING_INTERVAL_MS = 30_000L
        const val STATIONARY_INTERVAL_MS = 300_000L
        private const val CONFIRMATION_WINDOW_MS = 60_000L
    }
}
