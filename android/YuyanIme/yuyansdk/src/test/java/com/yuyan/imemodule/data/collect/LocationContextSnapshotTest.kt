package com.yuyan.imemodule.data.collect

import android.Manifest
import android.app.Application
import android.location.Location
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 31])
class LocationContextSnapshotTest {
    @Test fun `network speed without trustworthy accuracy keeps raw value and reports unreliable quality`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val location = Location("network").apply { accuracy = 100f; speed = 50.2f / 3.6f }
        val snapshot = LocationContextSnapshot.capture(context, location, "balanced")
        val encoded = Json.parseToJsonElement(Json.encodeToString(LocationContext.serializer(), snapshot)).jsonObject
        assertEquals("unreliable", encoded["speed_quality"]?.jsonPrimitive?.content)
        assertEquals(location.speed.toString(), encoded["raw_speed_mps"]?.jsonPrimitive?.content)
        assertEquals("poor_location_accuracy", encoded["speed_quality_reason"]?.jsonPrimitive?.content)
    }

    @Test fun `redacted wifi identifiers are never treated as real identifiers`() {
        assertNull(LocationContextSnapshot.cleanSsid("<unknown ssid>"))
        assertNull(LocationContextSnapshot.cleanSsid("\"\""))
        assertNull(LocationContextSnapshot.cleanBssid("02:00:00:00:00:00"))
        assertNull(LocationContextSnapshot.cleanBssid("ff:ff:ff:ff:ff:ff"))
        assertNull(LocationContextSnapshot.cleanSsid("bad\u0000ssid"))
        assertEquals("家里 Wi-Fi", LocationContextSnapshot.cleanSsid("\"家里 Wi-Fi\""))
        assertEquals("aa:bb:cc:dd:ee:ff", LocationContextSnapshot.cleanBssid("AA:BB:CC:DD:EE:FF"))
    }

    @Test fun `denied optional permissions preserve location report with unavailable context`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_WIFI_STATE)
        val location = Location("network").apply { latitude = 23.13; longitude = 113.3 }
        val snapshot = LocationContextSnapshot.capture(context, location, "balanced", 1_800_000_000_000)
        val report = LocationReport("test-device", location.latitude, location.longitude,
            occurredAt = "2027-01-15T08:00:00Z", context = snapshot)
        val body = Json.encodeToString(LocationReport.serializer(), report).let { Json.parseToJsonElement(it).jsonObject }
        assertEquals("23.13", body["latitude"]!!.jsonPrimitive.content)
        val captured = body["context"]!!.jsonObject
        assertEquals("1", captured["version"]!!.jsonPrimitive.content)
        assertEquals("balanced", captured["capture_mode"]!!.jsonPrimitive.content)
        assertEquals("permission_denied", captured["wifi"]!!.jsonObject["status"]!!.jsonPrimitive.content)
        assertNull(snapshot.altitudeM)
        assertNull(snapshot.bearingDeg)
    }
    @Test fun `connected wifi snapshot has connection metrics and drops malformed optional values`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_WIFI_STATE)
        val manager = context.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        val info = org.robolectric.shadows.ShadowWifiInfo.newInstance()
        shadowOf(info).apply {
            setSSID("Home"); setBSSID("aa:bb:cc:dd:ee:ff")
            setSupplicantState(android.net.wifi.SupplicantState.COMPLETED)
            setRssi(-55); setFrequency(5180); setLinkSpeed(866)
        }
        manager.isWifiEnabled = true
        shadowOf(manager).setConnectionInfo(info)
        val location = Location("gps").apply { altitude = 150_000.0 }
        val snapshot = LocationContextSnapshot.capture(context, location, "balanced")
        assertEquals("connected", snapshot.wifi?.status)
        assertEquals("Home", snapshot.wifi?.ssid)
        assertEquals(-55, snapshot.wifi?.rssi)
        assertEquals(5180, snapshot.wifi?.frequencyMhz)
        assertEquals(866, snapshot.wifi?.linkSpeedMbps)
        assertNull(snapshot.altitudeM)
        shadowOf(info).setFrequency(100001)
        shadowOf(info).setLinkSpeed(100001)
        val malformed = LocationContextSnapshot.capture(context, location, "balanced")
        assertNull(malformed.wifi?.frequencyMhz)
        assertNull(malformed.wifi?.linkSpeedMbps)
    }

}
