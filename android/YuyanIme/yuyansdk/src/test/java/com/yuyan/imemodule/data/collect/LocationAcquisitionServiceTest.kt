package com.yuyan.imemodule.data.collect

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 31])
class LocationAcquisitionServiceTest {
    private lateinit var app: Application
    private lateinit var manager: LocationManager
    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(app).edit().clear()
            .putBoolean(CollectionConsent.KEY, true).putBoolean(BalancedLocationService.KEY, true).commit()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        manager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        shadowOf(manager).setProviderEnabled("gps", true)
        shadowOf(manager).setProviderEnabled("network", true)
    }

    private fun advance(seconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))
    private fun gpsActive() = shadowOf(manager).getLocationUpdateListeners("gps").isNotEmpty()
    private fun fix(cached: Boolean = false) = Location("gps").apply {
        latitude = 23.13; longitude = 113.3; accuracy = 5f; time = System.currentTimeMillis()
        elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
        if (cached) extras = Bundle().apply { putString("realProvider", "GNSS_CACHE") }
    }
    private fun receive(location: Location) {
        shadowOf(manager).getLocationUpdateListeners("network").single().onLocationChanged(location)
        advance(5)
    }
    private fun prepareStayEvidence(service: BalancedLocationService) {
        // Handler's Robolectric clock does not advance java.lang.System wall time.
        val policy = org.robolectric.util.ReflectionHelpers.getField<BalancedLocationPolicy>(service, "policy")
        val now = System.currentTimeMillis()
        (0..3).forEach {
            val time = now - 122_000 + it * 30_000
            policy.observe(time, LocationCandidate(23.13, 113.3, 5f, time, "gps"), null, android.os.SystemClock.elapsedRealtime())
        }
    }

    @Test fun `gps without independent qualified results stops and retries with capped backoff`() {
        val controller = Robolectric.buildService(BalancedLocationService::class.java).create()
        try {
            controller.get().onStartCommand(Intent(), 0, 1)
            advance(120)
            assertFalse("unproductive GPS must stop at its 120 second budget", gpsActive())
            assertTrue(shadowOf(manager).getLocationUpdateListeners("network").isNotEmpty())
            assertTrue(shadowOf(manager).getLocationUpdateListeners("passive").isNotEmpty())
            for (delay in listOf(60L, 120L, 300L, 300L)) {
                advance(delay - 1); assertFalse(gpsActive())
                advance(1); assertTrue("retry must occur after the bounded cooldown", gpsActive())
                advance(120); assertFalse(gpsActive())
            }
        } finally { controller.destroy() }
    }

    @Test @Config(sdk = [31]) fun `modern gps requests have a native deadline even if app timers sleep`() {
        val controller = Robolectric.buildService(BalancedLocationService::class.java).create()
        try {
            controller.get().onStartCommand(Intent(), 0, 1)
            val request = shadowOf(manager).getLocationRequests("gps").single()
            assertTrue("GPS deadline must be enforced by the OS during app sleep", request.durationMillis in 1L..120_000L)
            assertEquals(60_000L, request.intervalMillis)
        } finally { controller.destroy() }
    }

    @Test fun `known cache cannot establish a stay or keep an unproductive gps session alive`() {
        val controller = Robolectric.buildService(BalancedLocationService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(Intent(), 0, 1)
            prepareStayEvidence(service)
            repeat(5) { receive(fix(cached = true)); advance(25) }
            val policy = org.robolectric.util.ReflectionHelpers.getField<BalancedLocationPolicy>(service, "policy")
            val geographicalInterval = org.robolectric.util.ReflectionHelpers.getField<Long>(policy, "samplingIntervalMs")
            assertEquals("stable cached coordinates are not stationary evidence", 60_000L, geographicalInterval)
            assertFalse("cached results cannot renew the GPS budget", gpsActive())
        } finally { controller.destroy() }
    }

    @Test fun `independent stable observations stop gps while network remains available`() {
        val controller = Robolectric.buildService(BalancedLocationService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(Intent(), 0, 1)
            prepareStayEvidence(service)
            receive(fix())
            assertFalse("confirmed stay releases GPS instead of only increasing its interval", gpsActive())
            assertTrue(shadowOf(manager).getLocationUpdateListeners("network").isNotEmpty())
            advance(600)
            assertTrue("ten minutes without a fresh observation needs a bounded check", gpsActive())
        } finally { controller.destroy() }
    }

    @Test fun `late cold start result can renew acquisition but its duplicate cannot renew again`() {
        val controller = Robolectric.buildService(BalancedLocationService::class.java).create()
        try {
            controller.get().onStartCommand(Intent(), 0, 1)
            advance(90)
            val first = fix()
            receive(first)
            advance(45)
            assertTrue("90 second cold start must not be cut off at the original deadline", gpsActive())
            receive(first)
            advance(70)
            assertFalse("duplicate callbacks must not lease GPS forever", gpsActive())
        } finally { controller.destroy() }
    }

    @Test fun `stop invalidates gps retry timers and late callbacks`() {
        val controller = Robolectric.buildService(BalancedLocationService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(Intent(), 0, 1)
            val old = shadowOf(manager).getLocationUpdateListeners("gps").single()
            advance(120)
            service.onStartCommand(Intent().setAction(BalancedLocationService.ACTION_STOP), 0, 2)
            old.onLocationChanged(fix())
            advance(600)
            assertTrue(shadowOf(manager).getLocationUpdateListeners().isEmpty())
            assertFalse(BalancedLocationService.isRunning)
        } finally { controller.destroy() }
    }

    @Test fun `independent qualified movement renews gps over a long ride`() {
        val controller = Robolectric.buildService(BalancedLocationService::class.java).create()
        try {
            controller.get().onStartCommand(Intent(), 0, 1)
            val captured = System.currentTimeMillis() - 30_000
            repeat(20) { index ->
                receive(fix().apply { time = captured + index * 1000; latitude += index * .001 })
                advance(55)
                assertTrue("productive movement must not be cut off by a fixed session timeout", gpsActive())
            }
        } finally { controller.destroy() }
    }

    @Test @Config(sdk = [31]) fun `trusted movement wakes gps after a confirmed stay`() {
        val controller = Robolectric.buildService(BalancedLocationService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(Intent(), 0, 1)
            prepareStayEvidence(service)
            receive(fix().apply { time -= 2000 })
            assertFalse(gpsActive())
            receive(fix().apply { provider = "network"; speed = 2f; speedAccuracyMetersPerSecond = .2f })
            assertTrue("current trusted movement can wake a parked GPS session", gpsActive())
        } finally { controller.destroy() }
    }

    @Test fun `movement confirmation leaves enough gps time for a ninety second cold start`() {
        val controller = Robolectric.buildService(BalancedLocationService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(Intent(), 0, 1)
            prepareStayEvidence(service)
            val now = System.currentTimeMillis()
            receive(fix().apply { time = now - 2000 })
            assertFalse(gpsActive())
            receive(fix().apply { provider = "network"; latitude += .001; time = now - 1000 })
            assertTrue(gpsActive())
            advance(90)
            assertTrue("unconfirmed movement must not stop GPS before a slow cold start", gpsActive())
            receive(fix().apply { latitude += .001; time = now - 500 })
            advance(15)
            receive(fix().apply { latitude += .0011; time = now })
            assertTrue("two independent GPS observations complete movement confirmation", gpsActive())
            assertEquals(60_000L, shadowOf(manager).getLegacyLocationRequests("network").single().intervalMillis)
        } finally { controller.destroy() }
    }

    @Test @Config(sdk = [31]) fun `fresh trusted movement can bypass a long cooldown only once without gps output`() {
        val controller = Robolectric.buildService(BalancedLocationService::class.java).create()
        try {
            controller.get().onStartCommand(Intent(), 0, 1)
            advance(120); advance(60); advance(120); advance(120); advance(120)
            assertFalse(gpsActive()) // Third failure: next scheduled attempt is 300 seconds away.
            advance(30)
            val now = System.currentTimeMillis()
            receive(fix().apply {
                provider = "network"; time = now - 2000; speed = 2f; speedAccuracyMetersPerSecond = .2f
            })
            assertTrue("new trusted movement deserves one early acquisition attempt", gpsActive())
            advance(120)
            assertFalse(gpsActive())
            receive(fix().apply {
                provider = "network"; time = now - 1000; speed = 2f; speedAccuracyMetersPerSecond = .2f
            })
            assertFalse("network speed cannot continually reset an unproductive GPS budget", gpsActive())
            advance(294); assertFalse("failure backoff must remain at its cap", gpsActive())
            advance(1); assertTrue(gpsActive())
        } finally { controller.destroy() }
    }
}
