package com.yuyan.imemodule.data.collect

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
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
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 31, 35])
class LocationSleepRecoveryTest {
    private lateinit var app: Application
    private lateinit var manager: LocationManager
    private lateinit var alarms: ShadowAlarmManager
    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(app).edit().clear()
            .putBoolean(CollectionConsent.KEY, true).putBoolean(BalancedLocationService.KEY, true).commit()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        manager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        shadowOf(manager).setProviderEnabled("gps", true)
        shadowOf(manager).setProviderEnabled("network", true)
        alarms = shadowOf(app.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
    }
    private fun locationAlarm() = alarms.scheduledAlarms.firstOrNull {
        it.tag == "balanced-location-deadline" ||
            it.operation?.let { operation -> shadowOf(operation).savedIntent.action?.endsWith(".LOCATION_DEADLINE") } == true
    }
    private fun sleepAndDeliver(seconds: Long) {
        val alarm = requireNotNull(locationAlarm()) { "elapsed wakeup missing for live GPS deadline" }
        assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, alarm.type)
        ShadowSystemClock.simulateDeepSleep(Duration.ofSeconds(seconds))
        alarms.fireAlarm(alarm)
        shadowOf(Looper.getMainLooper()).idle()
    }
    @Test fun sleepingGpsExpiresAndRetriesWithoutWaitingForUptime() {
        val controller = Robolectric.buildService(BalancedLocationService::class.java).create()
        try {
            controller.get().onStartCommand(Intent(), 0, 1)
            val uptime = SystemClock.uptimeMillis()
            sleepAndDeliver(121)
            assertEquals(uptime, SystemClock.uptimeMillis())
            assertTrue(shadowOf(manager).getLocationUpdateListeners("gps").isEmpty())
            sleepAndDeliver(60)
            assertTrue(shadowOf(manager).getLocationUpdateListeners("gps").isNotEmpty())
        } finally { controller.destroy() }
    }
    @Test fun stopCancelsWakeupAndLateDeliveryCannotRestartGps() {
        val controller = Robolectric.buildService(BalancedLocationService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(Intent(), 0, 1)
            val old = requireNotNull(locationAlarm())
            service.onStartCommand(Intent().setAction(BalancedLocationService.ACTION_STOP), 0, 2)
            assertNull(locationAlarm())
            ShadowSystemClock.simulateDeepSleep(Duration.ofMinutes(3))
            val oldListener = old.onAlarmListener
            if (oldListener != null) oldListener.onAlarm()
            else requireNotNull(old.operation).send()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(shadowOf(manager).getLocationUpdateListeners().isEmpty())
            assertNull(locationAlarm())
        } finally { controller.destroy() }
    }
    @Test fun activeDeadlineHasDozeEligibleBackup() {
        val controller = Robolectric.buildService(BalancedLocationService::class.java).create()
        try {
            controller.get().onStartCommand(Intent(), 0, 1)
            assertTrue("a normal exact alarm alone is deferred in Doze", alarms.scheduledAlarms.any {
                it.type == AlarmManager.ELAPSED_REALTIME_WAKEUP && it.isAllowWhileIdle &&
                    it.operation?.let { operation -> shadowOf(operation).savedIntent.action?.endsWith(".LOCATION_DEADLINE") } == true
            })
        } finally { controller.destroy() }
    }
    @Test fun permissionRevocationBeforeWakeCannotRestartLocation() {
        val controller = Robolectric.buildService(BalancedLocationService::class.java).create()
        try {
            controller.get().onStartCommand(Intent(), 0, 1)
            shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            sleepAndDeliver(121)
            assertTrue(shadowOf(manager).getLocationUpdateListeners().isEmpty())
            assertNull(locationAlarm())
        } finally { controller.destroy() }
    }
    @Test fun confirmedStayHasOnlyStaleObservationDeadline() {
        val controller = Robolectric.buildService(BalancedLocationService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(Intent(), 0, 1)
            val policy = ReflectionHelpers.getField<BalancedLocationPolicy>(service, "policy")
            val now = System.currentTimeMillis()
            (0..4).forEach { index ->
                val time = now - 120_000 + index * 30_000
                policy.observe(time, LocationCandidate(23.1, 113.2, 5f, time, "gps"), null, android.os.SystemClock.elapsedRealtime())
            }
            assertTrue(policy.isStationary)
            ReflectionHelpers.callInstanceMethod<Void>(service, "updateGpsRequest", ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false))
            assertNotNull(locationAlarm())
            assertEquals(600_000L, policy.recoveryDelayMs(SystemClock.elapsedRealtime()))
            assertTrue(shadowOf(manager).getLocationUpdateListeners("gps").isEmpty())
        } finally { controller.destroy() }
    }
}
