package com.yuyan.imemodule.data.collect

import android.location.Location

internal fun selectInitialLocation(nowMs: Long, locations: List<Location>): Location? {
    val eligible = locations.filter { location ->
        location.hasAccuracy() && LocationUploadPolicy.shouldUpload(nowMs, LocationCandidate(
            location.latitude, location.longitude, location.accuracy, location.time,
            knownCached = location.isKnownCachedFix()), null)
    }
    val newest = eligible.maxOfOrNull { it.time } ?: return null
    // 精度择优只在相近时间的真实定位中比较，不能用很旧的GPS遮盖刚发生的移动。
    return eligible.filter { newest - it.time <= 15_000 }
        .minWithOrNull(compareBy<Location> { it.accuracy }.thenByDescending { it.time })
}

internal fun Location.isKnownCachedFix(): Boolean = runCatching {
    val source = extras?.getString("realProvider") ?: return@runCatching false
    source.equals("CACHE", ignoreCase = true) || source.endsWith("_CACHE", ignoreCase = true)
}.getOrDefault(false)

internal data class ReceivedLocation(val location: Location, val receivedAtMs: Long, val receivedElapsedMs: Long)

/** One short, non-extending collection window; this never requests extra locations. */
internal class LocationFixSelection {
    private val candidates = java.util.TreeMap<Long, ReceivedLocation>()
    private var deadline: Long? = null
    private var lastSelectedTime: Long? = null

    fun delayMs(elapsedMs: Long): Long? = deadline?.let { (it - elapsedMs).coerceAtLeast(0) }

    fun offer(nowMs: Long, elapsedMs: Long, location: Location) {
        val eligible = selectInitialLocation(nowMs, listOf(location)) ?: return
        if (eligible.elapsedRealtimeNanos > 0 &&
            elapsedMs - eligible.elapsedRealtimeNanos / 1_000_000 !in 0L..60_000L) return
        if (deadline == null) deadline = elapsedMs + WINDOW_MS
        val existing = candidates[eligible.time]
        if (existing == null || eligible.accuracy < existing.location.accuracy) {
            candidates[eligible.time] = ReceivedLocation(Location(eligible), nowMs, elapsedMs)
        }
        // Preserve the newest timestamp even when an older fix is more accurate.
        val cutoff = candidates.lastKey() - 15_000L
        while (candidates.isNotEmpty() && candidates.firstKey() < cutoff) candidates.pollFirstEntry()
        // Bound callback floods. Prefer recency on overflow; this is deliberately not
        // a promise of global precision optimality across unlimited callbacks.
        while (candidates.size > MAX_CANDIDATES) candidates.pollFirstEntry()
    }

    fun take(nowMs: Long): ReceivedLocation? {
        // Freshness was checked when each callback arrived. Sleeping after receipt
        // cannot turn that observation into a stale last-known-location lookup.
        val received = candidates.values.filter { it.receivedAtMs <= nowMs }
        // A duplicate alone may retry a failed durable write. It must not erase a
        // newer independent observation merely because its reported error is lower.
        val independent = received.filter { it.location.time > (lastSelectedTime ?: Long.MIN_VALUE) }
        val usable = independent.ifEmpty { received }
        val newest = usable.maxOfOrNull { it.location.time }
        val selected = newest?.let { time ->
            usable.filter { time - it.location.time <= 15_000 }
                .minWithOrNull(compareBy<ReceivedLocation> { it.location.accuracy }
                    .thenByDescending { it.location.time })
        }
        selected?.let { lastSelectedTime = maxOf(lastSelectedTime ?: Long.MIN_VALUE, it.location.time) }
        candidates.clear(); deadline = null
        return selected
    }

    fun clear() { candidates.clear(); deadline = null; lastSelectedTime = null }

    companion object { const val WINDOW_MS = 5_000L; private const val MAX_CANDIDATES = 32 }
}
