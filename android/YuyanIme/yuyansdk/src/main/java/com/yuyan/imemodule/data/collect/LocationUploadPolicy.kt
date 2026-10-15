package com.yuyan.imemodule.data.collect

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

internal data class LocationCandidate(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val locationTimeMs: Long,
    val provider: String? = null,
    val elapsedRealtimeNanos: Long = 0L,
)

internal data class UploadedLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val locationTimeMs: Long,
    // 历史字段名保留：这是成功持久入队时间，不是服务端上传回执时间。
    val uploadedAtMs: Long,
    val provider: String? = null,
)

internal object LocationUploadPolicy {
    private const val MAX_LOCATION_AGE_MS = 60_000L
    private const val MIN_ENQUEUE_INTERVAL_MS = 60_000L
    private const val MAX_ACCURACY_METERS = 50f
    private const val EARTH_RADIUS_METERS = 6_371_000.0

    fun shouldUpload(
        nowMs: Long,
        candidate: LocationCandidate,
        lastUploaded: UploadedLocation?,
    ): Boolean {
        val ageMs = nowMs - candidate.locationTimeMs
        if (ageMs !in 0..MAX_LOCATION_AGE_MS) return false
        if (!candidate.accuracyMeters.isFinite() || candidate.accuracyMeters < 0f || candidate.accuracyMeters > MAX_ACCURACY_METERS) {
            return false
        }
        if (!candidate.latitude.isFinite() || candidate.latitude !in -90.0..90.0 ||
            !candidate.longitude.isFinite() || candidate.longitude !in -180.0..180.0) return false
        if (lastUploaded == null) return true
        if (candidate.locationTimeMs <= lastUploaded.locationTimeMs) return false
        // 每分钟保留有效变化点；网络按五分钟合批不能在这里丢弃中间位置。
        if (nowMs < lastUploaded.uploadedAtMs) return false
        // A genuine minute of capture time may tolerate one window of enqueue jitter.
        // Callback/IO latency can exceed the window; do not guess an upper bound for it.
        // Current-fix freshness and the 55-second durable-enqueue floor still apply.
        val savedInterval = nowMs - lastUploaded.uploadedAtMs
        if (savedInterval < MIN_ENQUEUE_INTERVAL_MS &&
            !(savedInterval >= MIN_ENQUEUE_INTERVAL_MS - LocationFixSelection.WINDOW_MS &&
                candidate.locationTimeMs - lastUploaded.locationTimeMs >= MIN_ENQUEUE_INTERVAL_MS)) return false

        val previous = LocationCandidate(lastUploaded.latitude, lastUploaded.longitude,
            lastUploaded.accuracyMeters, lastUploaded.locationTimeMs, lastUploaded.provider)
        return distanceMeters(previous, candidate) > movementThreshold(previous, candidate)
    }

    internal fun movementThreshold(from: LocationCandidate, to: LocationCandidate): Double {
        val precise = listOf(from, to).all {
            it.provider in setOf("gps", "fused") && it.accuracyMeters.isFinite() && it.accuracyMeters in 0f..25f
        }
        return max(if (precise) 10.0 else 50.0, from.accuracyMeters.toDouble() + to.accuracyMeters)
    }

    internal fun distanceMeters(from: LocationCandidate, to: LocationCandidate): Double {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val latitudeDelta = lat2 - lat1
        val longitudeDelta = Math.toRadians(to.longitude - from.longitude)
        val haversine = sin(latitudeDelta / 2) * sin(latitudeDelta / 2) +
            cos(lat1) * cos(lat2) * sin(longitudeDelta / 2) * sin(longitudeDelta / 2)
        return 2 * EARTH_RADIUS_METERS * asin(sqrt(haversine.coerceIn(0.0, 1.0)))
    }
}
