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
)

internal data class UploadedLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val locationTimeMs: Long,
    val uploadedAtMs: Long,
)

internal object LocationUploadPolicy {
    private const val MAX_LOCATION_AGE_MS = 60_000L
    private const val MIN_UPLOAD_INTERVAL_MS = 60_000L
    private const val MAX_ACCURACY_METERS = 200f
    private const val MIN_MOVEMENT_METERS = 50.0
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
        if (lastUploaded == null) return true
        if (nowMs - lastUploaded.uploadedAtMs < MIN_UPLOAD_INTERVAL_MS) return false

        val requiredMovement = max(
            MIN_MOVEMENT_METERS,
            lastUploaded.accuracyMeters.toDouble() + candidate.accuracyMeters.toDouble(),
        )
        return distanceMeters(lastUploaded, candidate) > requiredMovement
    }

    private fun distanceMeters(from: UploadedLocation, to: LocationCandidate): Double {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val latitudeDelta = lat2 - lat1
        val longitudeDelta = Math.toRadians(to.longitude - from.longitude)
        val haversine = sin(latitudeDelta / 2) * sin(latitudeDelta / 2) +
            cos(lat1) * cos(lat2) * sin(longitudeDelta / 2) * sin(longitudeDelta / 2)
        return 2 * EARTH_RADIUS_METERS * asin(sqrt(haversine.coerceIn(0.0, 1.0)))
    }
}
