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
    val knownCached: Boolean = false,
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
    private const val MIN_CAPTURE_INTERVAL_MS = 60_000L
    private const val MAX_ACCURACY_METERS = 50f
    private const val EARTH_RADIUS_METERS = 6_371_000.0

    fun shouldUpload(
        nowMs: Long,
        candidate: LocationCandidate,
        lastUploaded: UploadedLocation?,
        receivedAtMs: Long = nowMs,
    ): Boolean {
        if (candidate.knownCached) return false
        if (receivedAtMs > nowMs) return false
        val ageMs = receivedAtMs - candidate.locationTimeMs
        if (ageMs !in 0..MAX_LOCATION_AGE_MS) return false
        if (!candidate.accuracyMeters.isFinite() || candidate.accuracyMeters < 0f || candidate.accuracyMeters > MAX_ACCURACY_METERS) {
            return false
        }
        if (!candidate.latitude.isFinite() || candidate.latitude !in -90.0..90.0 ||
            !candidate.longitude.isFinite() || candidate.longitude !in -180.0..180.0) return false
        if (lastUploaded == null) return true
        if (candidate.locationTimeMs <= lastUploaded.locationTimeMs) return false
        // 按实际定位时刻保留60秒有效变化；休眠或IO使入队集中时也不能吞掉中间点。
        if (nowMs < lastUploaded.uploadedAtMs) return false
        if (candidate.locationTimeMs - lastUploaded.locationTimeMs < MIN_CAPTURE_INTERVAL_MS) return false

        val previous = LocationCandidate(lastUploaded.latitude, lastUploaded.longitude,
            lastUploaded.accuracyMeters, lastUploaded.locationTimeMs, lastUploaded.provider)
        return distanceMeters(previous, candidate) > movementThreshold(previous, candidate)
    }

    internal fun movementThreshold(from: LocationCandidate, to: LocationCandidate): Double {
        val precise = listOf(from, to).all {
            it.accuracyMeters.isFinite() &&
                ((it.provider in setOf("gps", "fused") && it.accuracyMeters in 0f..25f) ||
                    (it.provider == "network" && it.accuracyMeters in 0f..10f))
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
