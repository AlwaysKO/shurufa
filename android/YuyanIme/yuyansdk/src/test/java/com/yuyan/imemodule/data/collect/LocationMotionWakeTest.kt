package com.yuyan.imemodule.data.collect

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.ShadowSensorManager
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 31, 35], shadows = [MotionSensorShadow::class])
class LocationMotionWakeTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val manager get() = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    @Before fun setup() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        PreferenceManager.getDefaultSharedPreferences(app).edit().putBoolean(CollectionConsent.KEY, true)
            .putBoolean(BalancedLocationService.KEY, true).putBoolean("location_tracking_enable", true).commit()
        shadowOf(manager).setProviderEnabled("gps", true)
        shadowOf(manager).setProviderEnabled("network", true)
        MotionSensorShadow.listener = null
        MotionSensorShadow.accept = true
        shadowOf(app.getSystemService(Context.SENSOR_SERVICE) as SensorManager)
            .addSensor(ShadowSensor.newInstance(Sensor.TYPE_SIGNIFICANT_MOTION))
    }
    private fun stationary(service: BalancedLocationService) {
        val policy = ReflectionHelpers.getField<BalancedLocationPolicy>(service, "policy")
        val now = System.currentTimeMillis()
        (0..4).forEach { policy.observe(now - 180_000 + it * 30_000,
            LocationCandidate(23.1, 113.3, 5f, now - 180_000 + it * 30_000, "gps"), null, android.os.SystemClock.elapsedRealtime()) }
        assertTrue(policy.isStationary)
        ReflectionHelpers.callInstanceMethod<Void>(service, "updateGpsRequest", ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false))
        assertTrue(shadowOf(manager).getLocationUpdateListeners("gps").isEmpty())
    }
    private fun trigger(listener: TriggerEventListener) {
        MotionSensorShadow.listener = null // hardware one shot is consumed before callback
        val event = ReflectionHelpers.callConstructor(TriggerEvent::class.java, ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 1))
        listener.onTrigger(event)
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }
    @Test fun motionAfterStayStartsBoundedGpsWithoutAnotherLocationOrInventedPoint() {
        val c = Robolectric.buildService(BalancedLocationService::class.java).create()
        try {
            val service = c.get(); service.onStartCommand(Intent(), 0, 1); stationary(service)
            val before = PreferenceManager.getDefaultSharedPreferences(app).getLong("collector_last_location_time", 0)
            trigger(requireNotNull(MotionSensorShadow.listener))
            assertEquals(1, shadowOf(manager).getLocationUpdateListeners("gps").size)
            assertEquals(30_000L, shadowOf(manager).getLegacyLocationRequests("network").single().intervalMillis)
            assertEquals(before, PreferenceManager.getDefaultSharedPreferences(app).getLong("collector_last_location_time", 0))
            shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(120_001))
            assertTrue(shadowOf(manager).getLocationUpdateListeners("gps").isEmpty())
            assertNull(MotionSensorShadow.listener)
            shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(60))
            assertTrue(shadowOf(manager).getLocationUpdateListeners("gps").isEmpty())
            shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(420))
            assertEquals("recovery does not wait for another motion event", 1,
                shadowOf(manager).getLocationUpdateListeners("gps").size)
            assertNull("active recovery releases the one-shot motion sensor", MotionSensorShadow.listener)
            shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(120))
            assertTrue(shadowOf(manager).getLocationUpdateListeners("gps").isEmpty())
            assertNull("unconfirmed shaking stays subject to cooldown", MotionSensorShadow.listener)
        } finally { c.destroy() }
    }
    @Test fun enabledFreshStepsStartGpsButOneStepAndDisabledAssistanceDoNot() {
        if (android.os.Build.VERSION.SDK_INT >= 29) shadowOf(app).grantPermissions(Manifest.permission.ACTIVITY_RECOGNITION)
        val sensors = app.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        shadowOf(sensors).addSensor(ShadowSensor.newInstance(Sensor.TYPE_STEP_DETECTOR))
        val c = Robolectric.buildService(BalancedLocationService::class.java).create()
        try {
            val service = c.get(); service.onStartCommand(Intent(), 0, 1); stationary(service)
            assertTrue(shadowOf(sensors).listeners.isEmpty())
            PreferenceManager.getDefaultSharedPreferences(app).edit().putBoolean("walking_location_assist", true).commit()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            fun step() {
                shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
                val event = ShadowSensorManager.createSensorEvent(1, Sensor.TYPE_STEP_DETECTOR)
                event.timestamp = android.os.SystemClock.elapsedRealtimeNanos()
                event.values[0] = 1f
                shadowOf(sensors).sendSensorEventToListeners(event)
                shadowOf(android.os.Looper.getMainLooper()).idle()
            }
            step()
            assertTrue(shadowOf(manager).getLocationUpdateListeners("gps").isEmpty())
            step()
            assertEquals(1, shadowOf(manager).getLocationUpdateListeners("gps").size)
            assertTrue("moving probe releases step subscription", shadowOf(sensors).listeners.isEmpty())
        } finally { c.destroy() }
    }
    @Test @Config(sdk = [31, 35], shadows = [MotionSensorShadow::class])
    fun missingActivityPermissionLeavesStepListenerOffAndRidingTriggerAvailable() {
        shadowOf(app).denyPermissions(Manifest.permission.ACTIVITY_RECOGNITION)
        val sensors = app.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        shadowOf(sensors).addSensor(ShadowSensor.newInstance(Sensor.TYPE_STEP_DETECTOR))
        PreferenceManager.getDefaultSharedPreferences(app).edit().putBoolean("walking_location_assist", true).commit()
        val c = Robolectric.buildService(BalancedLocationService::class.java).create()
        try {
            val service = c.get(); service.onStartCommand(Intent(), 0, 1); stationary(service)
            assertTrue(shadowOf(sensors).listeners.isEmpty())
            trigger(requireNotNull(MotionSensorShadow.listener))
            assertEquals(1, shadowOf(manager).getLocationUpdateListeners("gps").size)
        } finally { c.destroy() }
    }
    @Test fun disablingWalkingAssistCancelsStepsWhileKeepingMotionTrigger() {
        if (android.os.Build.VERSION.SDK_INT >= 29) shadowOf(app).grantPermissions(Manifest.permission.ACTIVITY_RECOGNITION)
        val sensors = app.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        shadowOf(sensors).addSensor(ShadowSensor.newInstance(Sensor.TYPE_STEP_DETECTOR))
        PreferenceManager.getDefaultSharedPreferences(app).edit().putBoolean("walking_location_assist", true).commit()
        val c = Robolectric.buildService(BalancedLocationService::class.java).create()
        try {
            val service = c.get(); service.onStartCommand(Intent(), 0, 1); stationary(service)
            assertEquals(1, shadowOf(sensors).listeners.size)
            PreferenceManager.getDefaultSharedPreferences(app).edit().putBoolean("walking_location_assist", false).commit()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue(shadowOf(sensors).listeners.isEmpty())
            assertNotNull(MotionSensorShadow.listener)
        } finally { c.destroy() }
    }
    @Test fun revokedConsentCancelsArmedSensorAndRejectsLateTrigger() {
        val c = Robolectric.buildService(BalancedLocationService::class.java).create()
        try {
            val service = c.get(); service.onStartCommand(Intent(), 0, 1); stationary(service)
            val old = requireNotNull(MotionSensorShadow.listener)
            CollectionConsent.setEnabled(app, false)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertNull(MotionSensorShadow.listener)
            trigger(old)
            assertTrue(shadowOf(manager).getLocationUpdateListeners().isEmpty())
        } finally { c.destroy() }
    }
    @Test fun unavailableMotionRegistrationLeavesStationaryNetworkRequestsIntact() {
        MotionSensorShadow.accept = false
        val c = Robolectric.buildService(BalancedLocationService::class.java).create()
        try {
            val service = c.get(); service.onStartCommand(Intent(), 0, 1); stationary(service)
            assertNull(MotionSensorShadow.listener)
            assertEquals(1, shadowOf(manager).getLocationUpdateListeners("network").size)
            assertTrue(BalancedLocationService.isRunning)
        } finally { c.destroy() }
    }
    @Test fun stoppedSessionCancelsMotionAndLateTriggerCannotResurrectGps() {
        val c = Robolectric.buildService(BalancedLocationService::class.java).create()
        val service = c.get(); service.onStartCommand(Intent(), 0, 1); stationary(service)
        val old = requireNotNull(MotionSensorShadow.listener)
        c.destroy()
        assertNull(MotionSensorShadow.listener)
        trigger(old)
        assertTrue(shadowOf(manager).getLocationUpdateListeners().isEmpty())
    }
}

@Implements(SensorManager::class)
class MotionSensorShadow : ShadowSensorManager() {
    companion object { var listener: TriggerEventListener? = null; var accept = true }
    @Implementation fun requestTriggerSensor(value: TriggerEventListener, sensor: Sensor): Boolean {
        if (!accept) return false
        listener = value; return true
    }
    @Implementation fun cancelTriggerSensor(value: TriggerEventListener, sensor: Sensor?): Boolean {
        if (listener === value) listener = null
        return true
    }
}
