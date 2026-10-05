package com.yuyan.imemodule.data.collect

import kotlin.math.max

/** Local motion confirmation; noise inside the stationary area must not drag its anchor. */
internal class LocationJumpFilter {
    private var anchor: LocationCandidate? = null
    private var lastAccepted: LocationCandidate? = null
    private val pending = LinkedHashMap<String?, LocationCandidate>()
    private var latest: LocationCandidate? = null
    private var recentPrecise: LocationCandidate? = null

    val needsConfirmation: Boolean get() = pending.isNotEmpty()

    fun accept(nowMs: Long, candidate: LocationCandidate, speedMps: Float?, baseline: UploadedLocation? = null): Boolean {
        if (!LocationUploadPolicy.shouldUpload(nowMs, candidate, null)) return false
        if (latest?.let { elapsed(it, candidate) <= 0 } == true) {
            // 已接受点可重试落盘；持久基准负责去重，待确认点不能靠重复投递确认。
            return candidate == latest && candidate == lastAccepted
        }
        latest = candidate
        if (anchor == null) anchor = baseline?.let {
            LocationCandidate(it.latitude, it.longitude, it.accuracyMeters, it.locationTimeMs, it.provider)
        }?.takeIf { LocationUploadPolicy.shouldUpload(it.locationTimeMs, it, null) }
        val old = anchor ?: return acceptPosition(candidate, moveAnchor = true)
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
        val uncertainty = old.accuracyMeters.toDouble() + candidate.accuracyMeters
        if (distance <= max(50.0, uncertainty)) return acceptPosition(candidate, moveAnchor = false)

        // Only this fix's credible moving speed can support its displacement. Missing
        // observations or an earlier speed are not evidence that motion continued.
        val reference = lastAccepted ?: old
        val motionInterval = elapsed(reference, candidate)
        val speedSupportsMove = speedMps != null && speedMps.isFinite() && speedMps > 1.5f &&
            motionInterval in 1L..600_000L &&
            LocationUploadPolicy.distanceMeters(reference, candidate) <=
                speedMps * 1.5 * (motionInterval / 1000.0) + reference.accuracyMeters + candidate.accuracyMeters
        if (speedSupportsMove) return acceptPosition(candidate, moveAnchor = true)

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
                return acceptPosition(candidate, moveAnchor = true)
            }
        }
        pending.remove(candidate.provider)
        pending[candidate.provider] = candidate
        while (pending.size > 3) pending.remove(pending.keys.first())
        return false
    }

    private fun acceptPosition(candidate: LocationCandidate, moveAnchor: Boolean): Boolean {
        if (moveAnchor) anchor = candidate
        lastAccepted = candidate
        if (isPreciseSatelliteFix(candidate)) recentPrecise = candidate
        pending.clear()
        return true
    }

    private fun isPreciseSatelliteFix(candidate: LocationCandidate): Boolean =
        candidate.provider in setOf("gps", "fused") && candidate.accuracyMeters <= 25f

    private fun elapsed(from: LocationCandidate, to: LocationCandidate): Long =
        if (from.elapsedRealtimeNanos > 0 && to.elapsedRealtimeNanos > 0)
            (to.elapsedRealtimeNanos - from.elapsedRealtimeNanos) / 1_000_000
        else to.locationTimeMs - from.locationTimeMs
}
