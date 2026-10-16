package com.yuyan.imemodule.data.collect

import kotlin.math.max

/** Local motion confirmation; noise inside the stationary area must not drag its anchor. */
internal class LocationJumpFilter {
    private var anchor: LocationCandidate? = null
    private var lastAccepted: LocationCandidate? = null
    private val pending = LinkedHashMap<String?, LocationCandidate>()
    private var latest: LocationCandidate? = null
    private var recentPrecise: LocationCandidate? = null
    private var previousAccepted: LocationCandidate? = null
    private var lastAcceptedMotion = false

    var hasCurrentMotionEvidence = false
        private set

    val needsConfirmation: Boolean get() = pending.isNotEmpty()

    fun accept(nowMs: Long, candidate: LocationCandidate, speedMps: Float?, baseline: UploadedLocation? = null): Boolean {
        hasCurrentMotionEvidence = false
        if (!LocationUploadPolicy.shouldUpload(nowMs, candidate, null)) return false
        if (latest?.let { elapsed(it, candidate) <= 0 } == true) {
            // 已接受点可重试落盘；持久基准负责去重，待确认点不能靠重复投递确认。
            val retry = candidate == latest && candidate == lastAccepted
            if (retry) hasCurrentMotionEvidence = lastAcceptedMotion
            return retry
        }
        latest = candidate
        if (anchor == null) anchor = baseline?.let {
            LocationCandidate(it.latitude, it.longitude, it.accuracyMeters, it.locationTimeMs, it.provider)
        }?.takeIf { LocationUploadPolicy.shouldUpload(it.locationTimeMs, it, null) }
        val old = anchor ?: return acceptPosition(candidate, moveAnchor = true,
            moving = speedMps?.let { it.isFinite() && it > 0.3f } == true)
        val precise = recentPrecise ?: old.takeIf(::isPreciseSatelliteFix)
        if (candidate.provider == "network" && precise != null &&
            elapsed(precise, candidate) in 0L..60_000L &&
            candidate.accuracyMeters >= max(precise.accuracyMeters * 2f, precise.accuracyMeters + 10f)) {
            // A worse source neither starts nor erases a motion confirmation.
            return false
        }
        val interval = elapsed(old, candidate)
        if (interval <= 0) return false
        val distance = LocationUploadPolicy.distanceMeters(old, candidate)
        // Only this fix's credible moving speed can support its displacement. Missing
        // observations or an earlier speed are not evidence that motion continued.
        val reference = lastAccepted ?: old
        val motionInterval = elapsed(reference, candidate)
        val speedSupportsMove = speedMps != null && speedMps.isFinite() && speedMps > 0.3f &&
            motionInterval in 1L..600_000L &&
            LocationUploadPolicy.distanceMeters(reference, candidate) <=
                speedMps * 1.5 * (motionInterval / 1000.0) + reference.accuracyMeters + candidate.accuracyMeters
        val continuousMove = continuousMotion(previousAccepted, lastAccepted, candidate)
        if (distance <= LocationUploadPolicy.movementThreshold(old, candidate)) {
            return acceptPosition(candidate, moveAnchor = false, moving = speedSupportsMove || continuousMove)
        }
        if (speedSupportsMove || continuousMove) return acceptPosition(candidate, moveAnchor = true, moving = true)

        val previousPending = pending[candidate.provider]
        if (previousPending != null && elapsed(previousPending, candidate) in 1L..600_000L) {
            val previousDistance = LocationUploadPolicy.distanceMeters(old, previousPending)
            val step = LocationUploadPolicy.distanceMeters(previousPending, candidate)
            val error = previousPending.accuracyMeters.toDouble() + candidate.accuracyMeters
            val sameSide = (previousDistance * previousDistance + distance * distance - step * step) /
                (2 * previousDistance * distance) >= 0.5
            val nearPending = step <= max(50.0, error)
            val continuingOutward = distance > previousDistance && distance <= previousDistance * 3 + error
            // Direction and growth tolerances are conservative product choices, not a
            // speed estimate. Outward progress also allows vehicles with unknown speed.
            if (sameSide && (nearPending || continuingOutward)) {
                // Frequent passive fixes must not restart the one-second observation
                // window. Reversed or changed-source fixes cannot use this shortcut.
                if (elapsed(previousPending, candidate) < 1_000) return false
                return acceptPosition(candidate, moveAnchor = true, moving = true)
            }
        }
        pending.remove(candidate.provider)
        pending[candidate.provider] = candidate
        while (pending.size > 3) pending.remove(pending.keys.first())
        return false
    }

    private fun acceptPosition(candidate: LocationCandidate, moveAnchor: Boolean, moving: Boolean = false): Boolean {
        if (moveAnchor) anchor = candidate
        previousAccepted = lastAccepted
        lastAccepted = candidate
        hasCurrentMotionEvidence = moving
        lastAcceptedMotion = moving
        if (isPreciseSatelliteFix(candidate)) recentPrecise = candidate
        pending.clear()
        return true
    }

    /** Three current independent fixes must show progress beyond their own error,
     * a consistent direction, and bounded observation gaps and growth. */
    private fun continuousMotion(a: LocationCandidate?, b: LocationCandidate?, c: LocationCandidate): Boolean {
        if (a == null || b == null || a.provider != b.provider || b.provider != c.provider) return false
        if (a.locationTimeMs >= b.locationTimeMs || b.locationTimeMs >= c.locationTimeMs ||
            elapsed(a, b) !in 1_000L..120_000L || elapsed(b, c) !in 1_000L..120_000L) return false
        val first = LocationUploadPolicy.distanceMeters(a, b)
        val step = LocationUploadPolicy.distanceMeters(b, c)
        val progress = LocationUploadPolicy.distanceMeters(a, c)
        val error = b.accuracyMeters.toDouble() + c.accuracyMeters
        if (first <= a.accuracyMeters + b.accuracyMeters || step <= error ||
            progress <= LocationUploadPolicy.movementThreshold(a, c) || step > first * 3 + error) return false
        return (progress * progress - first * first - step * step) / (2 * first * step) >= 0.5
    }

    private fun isPreciseSatelliteFix(candidate: LocationCandidate): Boolean =
        candidate.provider in setOf("gps", "fused") && candidate.accuracyMeters <= 25f

    private fun elapsed(from: LocationCandidate, to: LocationCandidate): Long =
        if (from.elapsedRealtimeNanos > 0 && to.elapsedRealtimeNanos > 0)
            (to.elapsedRealtimeNanos - from.elapsedRealtimeNanos) / 1_000_000
        else to.locationTimeMs - from.locationTimeMs
}
