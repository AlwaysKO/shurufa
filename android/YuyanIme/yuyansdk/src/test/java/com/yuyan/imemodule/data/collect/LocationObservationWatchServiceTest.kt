package com.yuyan.imemodule.data.collect

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.location.Location
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
class LocationObservationWatchServiceTest {
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
    private fun gpsActive() = shadowOf(manager).getLocationUpdateListeners("gps").isNotEmpty()
    private fun advance(seconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))
    private fun establishStay(service: BalancedLocationService, now: Long = System.currentTimeMillis()) {
        val policy = ReflectionHelpers.getField<BalancedLocationPolicy>(service, "policy")
        (0..4).forEach {
            val time = now - 120_000 + it * 30_000L
            policy.observe(time, LocationCandidate(23.13, 113.3, 30f, time, "network"), null, SystemClock.elapsedRealtime())
        }
        assertTrue(policy.isStationary)
        ReflectionHelpers.callInstanceMethod<Void>(service, "updateGpsRequest", ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false))
        assertFalse(gpsActive())
    }
    private fun sleepAndWake(seconds: Long) {
        val alarms = shadowOf(app.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
        val alarm = requireNotNull(alarms.scheduledAlarms.firstOrNull {
            it.tag == "balanced-location-deadline" ||
                it.operation?.let { pi -> shadowOf(pi).savedIntent.action?.endsWith(".LOCATION_DEADLINE") } == true
        })
        ShadowSystemClock.simulateDeepSleep(Duration.ofSeconds(seconds))
        alarms.fireAlarm(alarm)
        shadowOf(Looper.getMainLooper()).idle()
    }
    @Test fun silentStayStartsBoundedCheckWithinTenMinutesWithoutSensorEvents() {
        val controller = Robolectric.buildService(BalancedLocationService::class.java).create()
        try {
            controller.get().onStartCommand(Intent(), 0, 1)
            establishStay(controller.get())
            advance(599); assertFalse(gpsActive())
            advance(1); assertTrue("silent stationary state needs a stale-observation check", gpsActive())
            advance(120); assertFalse(gpsActive())
            advance(479); assertFalse(gpsActive())
            advance(1); assertTrue("no result retains low-frequency checks instead of giving up", gpsActive())
        } finally { controller.destroy() }
    }
    @Test fun freshStationaryCallbacksKeepGpsOffAcrossThreeWatchPeriods() {
        val controller = Robolectric.buildService(BalancedLocationService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(Intent(), 0, 1)
            // Robolectric's wall clock need not advance with elapsed time. Keep
            // independent fix wall times inside the real freshness window.
            val base = System.currentTimeMillis() - 60_000
            establishStay(service, base)
            repeat(30) { index ->
                advance(55)
                val fix = Location("network").apply {
                    latitude = 23.13; longitude = 113.3; accuracy = 30f
                    time = base + (index + 1) * 1_000L
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                }
                shadowOf(manager).getLocationUpdateListeners("network").single().onLocationChanged(fix)
                advance(5)
                assertFalse("fresh stay must keep GPS off at minute ${index + 1}", gpsActive())
                val policy = ReflectionHelpers.getField<BalancedLocationPolicy>(service, "policy")
                assertTrue(policy.recoveryDelayMs(SystemClock.elapsedRealtime())!! >= 590_000)
            }
        } finally { controller.destroy() }
    }
    @Test fun lateConfirmationWakeStartsOneNewWindowImmediately() {
        val controller = Robolectric.buildService(BalancedLocationService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(Intent(), 0, 1)
            establishStay(service)
            ReflectionHelpers.callInstanceMethod<Void>(service, "beginMotionProbe")
            val uptime = SystemClock.uptimeMillis()
            sleepAndWake(900)
            assertEquals(uptime, SystemClock.uptimeMillis())
            assertTrue("late wake must reacquire now instead of scheduling another wake", gpsActive())
            sleepAndWake(120); assertFalse(gpsActive())
        } finally { controller.destroy() }
    }
    @Test fun lateMovingGpsExpiryAlsoReacquiresDuringTheSameWake() {
        val controller = Robolectric.buildService(BalancedLocationService::class.java).create()
        try {
            controller.get().onStartCommand(Intent(), 0, 1)
            sleepAndWake(900)
            assertTrue("expired GPS and already elapsed backoff must not spend this wake only stopping", gpsActive())
            if (android.os.Build.VERSION.SDK_INT >= 31)
                assertEquals(120_000L, shadowOf(manager).getLocationRequests("gps").single().durationMillis)
        } finally { controller.destroy() }
    }
}
