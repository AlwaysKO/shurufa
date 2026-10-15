package com.yuyan.imemodule.data.collect

import android.location.Location

internal fun selectInitialLocation(nowMs: Long, locations: List<Location>): Location? {
    val eligible = locations.filter { location ->
        location.hasAccuracy() && LocationUploadPolicy.shouldUpload(nowMs, LocationCandidate(
            location.latitude, location.longitude, location.accuracy, location.time), null)
    }
    val newest = eligible.maxOfOrNull { it.time } ?: return null
    // 精度择优只在相近时间的真实定位中比较，不能用很旧的GPS遮盖刚发生的移动。
    return eligible.filter { newest - it.time <= 15_000 }
        .minWithOrNull(compareBy<Location> { it.accuracy }.thenByDescending { it.time })
}

/** One short, non-extending collection window; this never requests extra locations. */
internal class LocationFixSelection {
    private val candidates = java.util.TreeMap<Long, Location>()
    private var deadline: Long? = null

    fun delayMs(elapsedMs: Long): Long? = deadline?.let { (it - elapsedMs).coerceAtLeast(0) }

    fun offer(nowMs: Long, elapsedMs: Long, location: Location) {
        val eligible = selectInitialLocation(nowMs, listOf(location)) ?: return
        if (deadline == null) deadline = elapsedMs + WINDOW_MS
        val existing = candidates[eligible.time]
        if (existing == null || eligible.accuracy < existing.accuracy) {
            candidates[eligible.time] = Location(eligible)
        }
        // Preserve the newest timestamp even when an older fix is more accurate.
        val cutoff = candidates.lastKey() - 15_000L
        while (candidates.isNotEmpty() && candidates.firstKey() < cutoff) candidates.pollFirstEntry()
        // Bound callback floods. Prefer recency on overflow; this is deliberately not
        // a promise of global precision optimality across unlimited callbacks.
        while (candidates.size > MAX_CANDIDATES) candidates.pollFirstEntry()
    }

    fun take(nowMs: Long): Location? {
        val selected = selectInitialLocation(nowMs, candidates.values.toList())
        clear()
        return selected
    }

    fun clear() { candidates.clear(); deadline = null }

    companion object { const val WINDOW_MS = 5_000L; private const val MAX_CANDIDATES = 32 }
}
