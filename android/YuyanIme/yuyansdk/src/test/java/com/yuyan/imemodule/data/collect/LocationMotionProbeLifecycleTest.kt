package com.yuyan.imemodule.data.collect

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 31, 35])
class LocationMotionProbeLifecycleTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val manager get() = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val main get() = shadowOf(android.os.Looper.getMainLooper())

    @Before fun setup() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        PreferenceManager.getDefaultSharedPreferences(app).edit()
            .putBoolean(CollectionConsent.KEY, true).putBoolean(BalancedLocationService.KEY, true)
            .putBoolean("location_tracking_enable", true).putBoolean(BalancedLocationService.WALKING_ASSIST_KEY, false).commit()
        shadowOf(manager).setProviderEnabled("gps", true)
        shadowOf(manager).setProviderEnabled("network", true)
    }

    private fun startProbe(service: BalancedLocationService) {
        service.onStartCommand(Intent(), 0, 1)
        val policy = ReflectionHelpers.getField<BalancedLocationPolicy>(service, "policy")
        val now = System.currentTimeMillis()
        (0..4).forEach {
            val time = now - 180_000L + it * 30_000L
            policy.observe(time, LocationCandidate(23.13, 113.3, 30f, time, "network"), null, android.os.SystemClock.elapsedRealtime())
        }
        assertTrue(policy.isStationary)
        ReflectionHelpers.callInstanceMethod<Void>(service, "updateGpsRequest", ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false))
        assertTrue(shadowOf(manager).getLocationUpdateListeners("gps").isEmpty())
        ReflectionHelpers.callInstanceMethod<Void>(service, "beginMotionProbe")
        assertEquals(1, shadowOf(manager).getLocationUpdateListeners("gps").size)
    }

    @Test fun inconclusiveNetworkCallbackLeavesGpsUntilOriginalDeadline() {
        val controller = Robolectric.buildService(BalancedLocationService::class.java).create()
        try {
            val service = controller.get()
            startProbe(service)
            main.idleFor(Duration.ofSeconds(1))
            val fix = Location("network").apply {
                latitude = 23.13; longitude = 113.3; accuracy = 30f
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
            }
            shadowOf(manager).getLocationUpdateListeners("network").single().onLocationChanged(fix)
            main.idleFor(Duration.ofMillis(5_001))
            assertEquals("first network callback must not cut the GPS cold start short", 1,
                shadowOf(manager).getLocationUpdateListeners("gps").size)
            main.idleFor(Duration.ofMillis(113_998))
            assertEquals(1, shadowOf(manager).getLocationUpdateListeners("gps").size)
            main.idleFor(Duration.ofMillis(2))
            assertTrue("no evidence must still release GPS at the original 120 seconds",
                shadowOf(manager).getLocationUpdateListeners("gps").isEmpty())
            val policy = ReflectionHelpers.getField<BalancedLocationPolicy>(service, "policy")
            assertTrue(policy.isStationary)
            assertTrue(policy.motionProbeDelayMs(android.os.SystemClock.elapsedRealtime()) in 479_998L..480_000L)
        } finally { controller.destroy() }
    }

    @Test fun stoppedProbeTimerCannotRestartGps() {
        val controller = Robolectric.buildService(BalancedLocationService::class.java).create()
        startProbe(controller.get())
        controller.destroy()
        main.idleFor(Duration.ofSeconds(181))
        assertTrue(shadowOf(manager).getLocationUpdateListeners().isEmpty())
        assertFalse(BalancedLocationService.isRunning)
    }
}
