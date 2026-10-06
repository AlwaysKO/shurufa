package com.yuyan.imemodule.data.collect

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.SupplicantState
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Serializable
internal data class LocationContext(
    // No default: version must be encoded even when the serializer omits defaults.
    val version: Int,
    @SerialName("captured_at") val capturedAt: String,
    @SerialName("capture_mode") val captureMode: String,
    @SerialName("network_type") val networkType: String = "unknown",
    val wifi: ConnectedWifi? = null,
    @SerialName("battery_percent") val batteryPercent: Int? = null,
    val charging: Boolean? = null,
    @SerialName("is_interactive") val isInteractive: Boolean? = null,
    @SerialName("power_save") val powerSave: Boolean? = null,
    @SerialName("altitude_m") val altitudeM: Double? = null,
    @SerialName("bearing_deg") val bearingDeg: Float? = null,
    @SerialName("speed_accuracy_mps") val speedAccuracyMps: Float? = null,
    @SerialName("raw_speed_mps") val rawSpeedMps: Float? = null,
    @SerialName("speed_quality") val speedQuality: String? = null,
    @SerialName("speed_quality_reason") val speedQualityReason: String? = null,
)

@Serializable
internal data class ConnectedWifi(
    val status: String,
    val ssid: String? = null,
    val bssid: String? = null,
    val rssi: Int? = null,
    @SerialName("frequency_mhz") val frequencyMhz: Int? = null,
    @SerialName("link_speed_mbps") val linkSpeedMbps: Int? = null,
)

internal object LocationContextSnapshot {
    fun capture(context: Context, location: Location, mode: String, nowMs: Long = System.currentTimeMillis()): LocationContext {
        val speed = LocationSpeedQuality.from(location)
        val battery = runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        val percent = battery?.let {
            val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else null
        }
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return LocationContext(
            version = 1,
            capturedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date(nowMs)),
            captureMode = mode,
            networkType = runCatching { networkType(context) }.getOrDefault("unknown"),
            wifi = runCatching { connectedWifi(context) }.getOrElse {
                ConnectedWifi(if (it is SecurityException) "permission_denied" else "unavailable")
            },
            batteryPercent = percent,
            charging = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)?.takeIf { it >= 0 }?.let {
                it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL
            },
            isInteractive = runCatching { power?.isInteractive }.getOrNull(),
            powerSave = runCatching { power?.isPowerSaveMode }.getOrNull(),
            altitudeM = verifiedEllipsoidAltitude(location),
            bearingDeg = location.bearing.takeIf { location.hasBearing() && it.isFinite() && it >= 0 && it < 360 },
            speedAccuracyMps = if (Build.VERSION.SDK_INT >= 26) location.speedAccuracyMetersPerSecond
                .takeIf { location.hasSpeedAccuracy() && it.isFinite() && it in 0f..10000f } else null,
            rawSpeedMps = speed.rawSpeedMps,
            speedQuality = speed.quality,
            speedQualityReason = speed.reason,
        )
    }

    private fun verifiedEllipsoidAltitude(location: Location): Double? {
        if (Build.VERSION.SDK_INT < 26 || !location.hasAltitude() || !location.hasVerticalAccuracy()) return null
        // A conservative display threshold, not a guarantee: Android reports 68% uncertainty.
        // This remains WGS84 ellipsoid height; it is never converted or relabeled as sea level.
        val error = location.verticalAccuracyMeters
        if (!error.isFinite() || error !in 0f..20f) return null
        return location.altitude.takeIf { it.isFinite() && it in -20000.0..100000.0 }
    }

    private fun networkType(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return "unknown"
        val network = cm.activeNetwork ?: return "offline"
        val caps = cm.getNetworkCapabilities(network) ?: return "unknown"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
    }

    @Suppress("DEPRECATION")
    private fun connectedWifi(context: Context): ConnectedWifi {
        if (!granted(context, Manifest.permission.ACCESS_WIFI_STATE) || !granted(context, Manifest.permission.ACCESS_FINE_LOCATION)) {
            return ConnectedWifi("permission_denied")
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return ConnectedWifi("unavailable")
        if (!LocationManagerCompat.isLocationEnabled(lm)) return ConnectedWifi("location_disabled")
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return ConnectedWifi("unavailable")
        if (!wm.isWifiEnabled) return ConnectedWifi("disconnected")
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        // API 31+ exposes transportInfo, but synchronous capabilities may redact identifiers.
        // getConnectionInfo remains a permission-checked fallback for the current connection only.
        val transport = if (Build.VERSION.SDK_INT >= 31) cm?.activeNetwork?.let {
            cm.getNetworkCapabilities(it)?.transportInfo as? WifiInfo
        } else null
        val info = transport?.takeIf { cleanSsid(it.ssid) != null } ?: wm.connectionInfo
            ?: return ConnectedWifi("unavailable")
        if (info.supplicantState != SupplicantState.COMPLETED) return ConnectedWifi("disconnected")
        val ssid = cleanSsid(info.ssid)
        val bssid = cleanBssid(info.bssid)
        return ConnectedWifi(
            status = if (ssid == null && bssid == null) "unavailable" else "connected",
            ssid = ssid,
            bssid = bssid,
            rssi = info.rssi.takeIf { it in -126..-1 },
            frequencyMhz = info.frequency.takeIf { it in 1..100000 },
            linkSpeedMbps = info.linkSpeed.takeIf { it in 0..100000 },
        )
    }

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    internal fun cleanSsid(raw: String?): String? = raw?.removeSurrounding("\"")?.takeIf {
        it.isNotBlank() && !it.contains('\u0000') && !it.equals(WifiManager.UNKNOWN_SSID, ignoreCase = true)
    }?.take(128)

    internal fun cleanBssid(raw: String?): String? = raw?.lowercase(Locale.US)?.takeIf {
        it.matches(Regex("([0-9a-f]{2}:){5}[0-9a-f]{2}")) &&
            it != "02:00:00:00:00:00" && it != "00:00:00:00:00:00" && it != "ff:ff:ff:ff:ff:ff"
    }
}
