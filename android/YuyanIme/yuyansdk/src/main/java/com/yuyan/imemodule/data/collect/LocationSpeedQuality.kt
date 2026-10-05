package com.yuyan.imemodule.data.collect

import android.location.Location
import android.os.Build
import kotlin.math.max
import kotlin.math.min

internal data class LocationSpeedQuality(
    val rawSpeedMps: Float?,
    val speedMps: Float?,
    val quality: String,
    val reason: String,
) {
    companion object {
        fun from(location: Location): LocationSpeedQuality {
            val raw = location.speed.takeIf { location.hasSpeed() && it.isFinite() && it >= 0 }
                ?: return LocationSpeedQuality(null, null, "unavailable", "missing_speed")
            // Conservative display policy, not a guarantee of the device's actual speed.
            // Android's speed accuracy is a 68% confidence uncertainty, not a speed limit.
            val reason = when {
                !location.hasAccuracy() || !location.accuracy.isFinite() || location.accuracy !in 0f..50f ->
                    "poor_location_accuracy"
                Build.VERSION.SDK_INT < 26 || !location.hasSpeedAccuracy() -> "missing_speed_accuracy"
                !location.speedAccuracyMetersPerSecond.isFinite() ||
                    location.speedAccuracyMetersPerSecond !in 0f..max(1.5f, min(5f, raw * 0.25f)) ->
                    "poor_speed_accuracy"
                else -> "accurate"
            }
            val trusted = reason == "accurate"
            return LocationSpeedQuality(raw, raw.takeIf { trusted }, if (trusted) "trusted" else "unreliable", reason)
        }
    }
}
