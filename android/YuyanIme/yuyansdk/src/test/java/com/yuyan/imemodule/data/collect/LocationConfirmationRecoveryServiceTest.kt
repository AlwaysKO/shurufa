package com.yuyan.imemodule.data.collect

import android.Manifest
import android.app.Application
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
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
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 31, 35])
class LocationConfirmationRecoveryServiceTest {
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
    private fun startProbe(service: BalancedLocationService) {
        service.onStartCommand(Intent(), 0, 1)
        val policy = ReflectionHelpers.getField<BalancedLocationPolicy>(service, "policy")
        val now = System.currentTimeMillis()
        (0..4).forEach {
            val time = now - 180_000 + it * 30_000
            policy.observe(time, LocationCandidate(23.13, 113.3, 30f, time, "network"), null, android.os.SystemClock.elapsedRealtime())
        }
        assertTrue(policy.isStationary)
        ReflectionHelpers.callInstanceMethod<Void>(service, "updateGpsRequest", ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false))
        ReflectionHelpers.callInstanceMethod<Void>(service, "beginMotionProbe")
        assertTrue(gpsActive())
    }
    @Test fun failedProbeKeepsLowFrequencyBoundedChecksWithoutAnotherSensorEvent() {
        val controller = Robolectric.buildService(BalancedLocationService::class.java).create()
        try {
            startProbe(controller.get())
            repeat(4) {
                advance(120); assertFalse(gpsActive())
                advance(479); assertFalse(gpsActive())
                advance(1); assertTrue("missing evidence retains a low-frequency check", gpsActive())
            }
            assertTrue(shadowOf(manager).getLocationUpdateListeners("network").isNotEmpty())
        } finally { controller.destroy() }
    }
    @Test fun explicitStopCancelsPendingRecovery() {
        val controller = Robolectric.buildService(BalancedLocationService::class.java).create()
        try {
            startProbe(controller.get())
            advance(120)
            controller.get().onStartCommand(Intent().setAction(BalancedLocationService.ACTION_STOP), 0, 2)
            advance(3600)
            assertTrue(shadowOf(manager).getLocationUpdateListeners().isEmpty())
            assertFalse(BalancedLocationService.isRunning)
        } finally { controller.destroy() }
    }
    @Test fun permissionRevocationCancelsPendingRecovery() {
        val controller = Robolectric.buildService(BalancedLocationService::class.java).create()
        try {
            startProbe(controller.get()); advance(120)
            shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            advance(480)
            assertTrue(shadowOf(manager).getLocationUpdateListeners().isEmpty())
            assertFalse(BalancedLocationService.isRunning)
        } finally { controller.destroy() }
    }
    @Test fun sleepingDuringCooldownStartsOneFreshAttemptAndKeepsTheInterval() {
        val controller = Robolectric.buildService(BalancedLocationService::class.java).create()
        try {
            startProbe(controller.get()); advance(120)
            val alarms = shadowOf(app.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
            fun sleepAndWake(seconds: Long) {
                val alarm = requireNotNull(alarms.scheduledAlarms.firstOrNull {
                    it.tag == "balanced-location-deadline" ||
                        it.operation?.let { operation -> shadowOf(operation).savedIntent.action?.endsWith(".LOCATION_DEADLINE") } == true
                })
                ShadowSystemClock.simulateDeepSleep(Duration.ofSeconds(seconds))
                alarms.fireAlarm(alarm)
                shadowOf(Looper.getMainLooper()).idle()
            }
            val uptime = SystemClock.uptimeMillis()
            sleepAndWake(900)
            assertEquals(uptime, SystemClock.uptimeMillis())
            assertTrue("elapsed wakeup must recover without another sensor callback", gpsActive())
            sleepAndWake(120); assertFalse(gpsActive())
            sleepAndWake(480); assertTrue(gpsActive())
            sleepAndWake(120); assertFalse(gpsActive())
            ShadowSystemClock.simulateDeepSleep(Duration.ofSeconds(60))
            assertFalse(gpsActive())
            assertTrue("missing evidence retains a future low-frequency wakeup", alarms.scheduledAlarms.any {
                it.tag == "balanced-location-deadline" ||
                    it.operation?.let { operation -> shadowOf(operation).savedIntent.action?.endsWith(".LOCATION_DEADLINE") } == true
            })
        } finally { controller.destroy() }
    }
}
