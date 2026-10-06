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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 31, 35])
class BalancedLocationServiceTest {
    private lateinit var app: Application
    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(app).edit().clear().commit()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val lm = shadowOf(app.getSystemService(Context.LOCATION_SERVICE) as LocationManager)
        lm.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        lm.setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
    }

    @Test fun `service cannot collect before consent even if balanced setting is enabled`() {
        PreferenceManager.getDefaultSharedPreferences(app).edit().putBoolean(BalancedLocationService.KEY, true).commit()
        val controller = Robolectric.buildService(BalancedLocationService::class.java).create()
        val service = controller.get()
        service.onStartCommand(Intent(), 0, 1)
        assertFalse(BalancedLocationService.isRunning)
        assertTrue(shadowOf(service).isStoppedBySelf)
        controller.destroy()
    }

    @Test fun `foreground session owns listeners and notification stop removes them`() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(app)
        prefs.edit().putBoolean(CollectionConsent.KEY, true).putBoolean(BalancedLocationService.KEY, true).commit()
        val controller = Robolectric.buildService(BalancedLocationService::class.java).create()
        val service = controller.get()
        service.onStartCommand(Intent(), 0, 1)
        assertTrue(BalancedLocationService.isRunning)
        assertNotNull(shadowOf(service).lastForegroundNotification)
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        assertTrue(shadowOf(lm).getLocationUpdateListeners().isNotEmpty())
        service.onStartCommand(Intent().setAction(BalancedLocationService.ACTION_STOP), 0, 2)
        controller.destroy()
        assertFalse(BalancedLocationService.isRunning)
        assertFalse(prefs.getBoolean(BalancedLocationService.KEY, true))
        assertTrue(shadowOf(lm).getLocationUpdateListeners().isEmpty())
    }

    @Test fun `revoking consent stops the running session immediately`() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(app)
        prefs.edit().putBoolean(CollectionConsent.KEY, true).putBoolean(BalancedLocationService.KEY, true).commit()
        val controller = Robolectric.buildService(BalancedLocationService::class.java).create()
        val service = controller.get()
        service.onStartCommand(Intent(), 0, 1)
        prefs.edit().putBoolean(CollectionConsent.KEY, false).commit()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertFalse(BalancedLocationService.isRunning)
        controller.destroy()
    }
    @Test fun `revoking foreground permission removes callbacks before next sample`() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(app)
        prefs.edit().putBoolean(CollectionConsent.KEY, true).putBoolean(BalancedLocationService.KEY, true).commit()
        val controller = Robolectric.buildService(BalancedLocationService::class.java).create()
        val service = controller.get()
        service.onStartCommand(Intent(), 0, 1)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val appOps = app.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        shadowOf(appOps).setMode(android.app.AppOpsManager.OPSTR_FINE_LOCATION, app.applicationInfo.uid,
            app.packageName, android.app.AppOpsManager.MODE_IGNORED)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(BalancedLocationService.isRunning)
        assertTrue(shadowOf(service).isStoppedBySelf)
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        assertTrue(shadowOf(lm).getLocationUpdateListeners().isEmpty())
        controller.destroy()
    }

    @Test fun `denied location app ops stop collection even if permission grant remains`() {
        PreferenceManager.getDefaultSharedPreferences(app).edit().putBoolean(CollectionConsent.KEY, true)
            .putBoolean(BalancedLocationService.KEY, true).commit()
        val controller = Robolectric.buildService(BalancedLocationService::class.java).create()
        try {
            controller.get().onStartCommand(Intent(), 0, 1)
            val appOps = app.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
            for (op in listOf(android.app.AppOpsManager.OPSTR_FINE_LOCATION, android.app.AppOpsManager.OPSTR_COARSE_LOCATION)) {
                shadowOf(appOps).setMode(op, app.applicationInfo.uid, app.packageName, android.app.AppOpsManager.MODE_IGNORED)
            }
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertFalse(BalancedLocationService.isRunning)
            assertTrue(PreferenceManager.getDefaultSharedPreferences(app).getBoolean(BalancedLocationService.KEY, false))
        } finally { controller.destroy() }
    }

    @Test fun `permission fallback waits thirty minutes when platform notification is missing`() {
        PreferenceManager.getDefaultSharedPreferences(app).edit().putBoolean(CollectionConsent.KEY, true)
            .putBoolean(BalancedLocationService.KEY, true).commit()
        val controller = Robolectric.buildService(BalancedLocationService::class.java).create()
        try {
            controller.get().onStartCommand(Intent(), 0, 1)
            val main = shadowOf(android.os.Looper.getMainLooper())
            main.idle()
            // Change the permission state without dispatching an AppOps change event.
            shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            main.idleFor(java.time.Duration.ofMinutes(30).minusMillis(1))
            assertTrue(BalancedLocationService.isRunning)
            main.idleFor(java.time.Duration.ofMillis(1))
            assertFalse(BalancedLocationService.isRunning)
        } finally { controller.destroy() }
    }

    @Test fun `location callback checks revoked permission before observing or reporting`() {
        PreferenceManager.getDefaultSharedPreferences(app).edit().putBoolean(CollectionConsent.KEY, true)
            .putBoolean(BalancedLocationService.KEY, true).commit()
        val controller = Robolectric.buildService(BalancedLocationService::class.java).create()
        try {
            controller.get().onStartCommand(Intent(), 0, 1)
            val lm = shadowOf(app.getSystemService(Context.LOCATION_SERVICE) as LocationManager)
            val callback = lm.getLocationUpdateListeners(LocationManager.GPS_PROVIDER).single()
            shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            callback.onLocationChanged(Location("gps").apply { latitude = 23.13; longitude = 113.3; accuracy = 15f; time = System.currentTimeMillis() })
            assertFalse(BalancedLocationService.isRunning)
            assertTrue(lm.getLocationUpdateListeners().isEmpty())
        } finally { controller.destroy() }
    }

    @Test fun `stationary session replaces system requests with five minute interval and resumes movement`() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(app)
        prefs.edit().putBoolean(CollectionConsent.KEY, true).putBoolean(BalancedLocationService.KEY, true).commit()
        val controller = Robolectric.buildService(BalancedLocationService::class.java).create()
        val service = controller.get()
        service.onStartCommand(Intent(), 0, 1)
        val lm = shadowOf(app.getSystemService(Context.LOCATION_SERVICE) as LocationManager)
        fun sample(latitude: Double, capturedAt: Long) {
            val location = Location("gps").apply {
                this.latitude = latitude; longitude = 113.3; accuracy = 15f; time = capturedAt
            }
            lm.getLocationUpdateListeners(LocationManager.GPS_PROVIDER).toList().forEach { it.onLocationChanged(location) }
        }
        assertEquals(60_000L, lm.getLegacyLocationRequests(LocationManager.GPS_PROVIDER).single().intervalMillis)
        // Android 12+ encodes passive requests with PASSIVE_INTERVAL; minUpdateInterval is the delivery throttle.
        fun passiveInterval() = if (android.os.Build.VERSION.SDK_INT >= 31)
            lm.getLocationRequests(LocationManager.PASSIVE_PROVIDER).single().minUpdateIntervalMillis
        else lm.getLegacyLocationRequests(LocationManager.PASSIVE_PROVIDER).single().intervalMillis
        assertEquals(60_000L, passiveInterval())
        // Supply an observation history without making the test wait two wall-clock minutes.
        val now = System.currentTimeMillis()
        val policy = org.robolectric.util.ReflectionHelpers.getField<BalancedLocationPolicy>(service, "policy")
        (0..3).forEach {
            val time = now - 122_000 + it * 30_000
            policy.observe(time, LocationCandidate(23.13, 113.3, 15f, time), null)
        }
        sample(23.13, now - 2_000)
        assertEquals(300_000L, lm.getLegacyLocationRequests(LocationManager.GPS_PROVIDER).single().intervalMillis)
        assertEquals(300_000L, passiveInterval())
        sample(23.14, now - 1_000)
        assertEquals(30_000L, lm.getLegacyLocationRequests(LocationManager.GPS_PROVIDER).single().intervalMillis)
        assertEquals(30_000L, passiveInterval())
        sample(23.1401, now)
        assertEquals(60_000L, lm.getLegacyLocationRequests(LocationManager.GPS_PROVIDER).single().intervalMillis)
        assertEquals(60_000L, passiveInterval())
        service.onStartCommand(Intent().setAction(BalancedLocationService.ACTION_STOP), 0, 2)
        controller.destroy()
    }

    @Test fun `confirmation requests return to five minutes without another location callback`() {
        PreferenceManager.getDefaultSharedPreferences(app).edit().putBoolean(CollectionConsent.KEY, true)
            .putBoolean(BalancedLocationService.KEY, true).commit()
        val controller = Robolectric.buildService(BalancedLocationService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(Intent(), 0, 1)
            val lm = shadowOf(app.getSystemService(Context.LOCATION_SERVICE) as LocationManager)
            val now = System.currentTimeMillis()
            val policy = org.robolectric.util.ReflectionHelpers.getField<BalancedLocationPolicy>(service, "policy")
            (0..3).forEach {
                val time = now - 122_000 + it * 30_000
                policy.observe(time, LocationCandidate(23.13, 113.3, 15f, time), null)
            }
            fun sample(latitude: Double, time: Long) {
                val location = Location("gps").apply {
                    this.latitude = latitude; longitude = 113.3; accuracy = 15f; this.time = time
                }
                lm.getLocationUpdateListeners(LocationManager.GPS_PROVIDER).toList().forEach { it.onLocationChanged(location) }
            }
            sample(23.13, now - 2000)
            assertEquals(300_000L, lm.getLegacyLocationRequests(LocationManager.GPS_PROVIDER).single().intervalMillis)
            sample(23.14, now - 1000)
            assertEquals(30_000L, lm.getLegacyLocationRequests(LocationManager.GPS_PROVIDER).single().intervalMillis)
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(60))
            assertEquals(300_000L, lm.getLegacyLocationRequests(LocationManager.GPS_PROVIDER).single().intervalMillis)
        } finally { controller.destroy() }
    }

    @Test fun `one disabled provider preserves subscriptions without a re-registration loop`() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(app)
        prefs.edit().putBoolean(CollectionConsent.KEY, true).putBoolean(BalancedLocationService.KEY, true).commit()
        val controller = Robolectric.buildService(BalancedLocationService::class.java).create()
        val service = controller.get()
        service.onStartCommand(Intent(), 0, 1)
        val main = shadowOf(android.os.Looper.getMainLooper())
        main.idle()
        val lm = shadowOf(app.getSystemService(Context.LOCATION_SERVICE) as LocationManager)
        val listener = lm.getLocationUpdateListeners(LocationManager.GPS_PROVIDER).single()
        // First deliver the callback without disabling the shadow provider: a bad
        // implementation fails identity checks instead of entering an infinite event loop.
        listener.onProviderDisabled(LocationManager.GPS_PROVIDER)
        assertTrue("Provider changes must not schedule an immediate re-registration", main.isIdle)
        assertSame(listener, lm.getLocationUpdateListeners(LocationManager.GPS_PROVIDER).single())
        assertSame(listener, lm.getLocationUpdateListeners(LocationManager.NETWORK_PROVIDER).single())
        lm.setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        main.idle()
        assertSame(listener, lm.getLocationUpdateListeners(LocationManager.NETWORK_PROVIDER).single())
        assertTrue(BalancedLocationService.isRunning)
        service.onStartCommand(Intent().setAction(BalancedLocationService.ACTION_STOP), 0, 2)
        controller.destroy()
    }

    @Test fun `process destruction preserves user choice and null intent resumes sticky service`() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(app)
        prefs.edit().putBoolean(CollectionConsent.KEY, true).putBoolean(BalancedLocationService.KEY, true).commit()
        val first = Robolectric.buildService(BalancedLocationService::class.java).create()
        try {
            assertEquals(android.app.Service.START_STICKY, first.get().onStartCommand(Intent(), 0, 1))
        } finally { first.destroy() }
        assertTrue("销毁不是用户关闭", prefs.getBoolean(BalancedLocationService.KEY, false))
        val restored = Robolectric.buildService(BalancedLocationService::class.java).create()
        try {
            assertEquals(android.app.Service.START_STICKY, restored.get().onStartCommand(null, 0, 2))
            assertTrue(BalancedLocationService.isRunning)
        } finally { restored.destroy() }
    }

    @Test fun `temporary permission loss pauses without clearing enabled preference`() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(app)
        prefs.edit().putBoolean(CollectionConsent.KEY, true).putBoolean(BalancedLocationService.KEY, true).commit()
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val controller = Robolectric.buildService(BalancedLocationService::class.java).create()
        try {
            assertEquals(android.app.Service.START_NOT_STICKY, controller.get().onStartCommand(null, 0, 1))
            assertFalse(BalancedLocationService.isRunning)
            assertTrue("缺权限只能暂停", prefs.getBoolean(BalancedLocationService.KEY, false))
        } finally { controller.destroy() }
    }

}
