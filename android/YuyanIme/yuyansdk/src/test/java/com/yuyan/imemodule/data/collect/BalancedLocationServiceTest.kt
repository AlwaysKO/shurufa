package com.yuyan.imemodule.data.collect

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
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
        network(true)
    }

    private fun network(wifi: Boolean, validated: Boolean = true) {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val shadow = shadowOf(cm)
        shadow.setActiveNetworkInfo(ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,
            if (wifi) ConnectivityManager.TYPE_WIFI else ConnectivityManager.TYPE_MOBILE, 0, true, true))
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addTransportType(if (wifi) NetworkCapabilities.TRANSPORT_WIFI else NetworkCapabilities.TRANSPORT_CELLULAR)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (validated) shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        shadow.setNetworkCapabilities(cm.activeNetwork, caps)
        shadow.networkCallbacks.toList().forEach { it.onCapabilitiesChanged(cm.activeNetwork!!, caps) }
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test @Config(sdk = [31], shadows = [FailingLocationManager::class])
    fun `failed provider registration during late flush cannot reopen stopped selection`() {
        PreferenceManager.getDefaultSharedPreferences(app).edit().putBoolean(CollectionConsent.KEY, true)
            .putBoolean(BalancedLocationService.KEY, true).commit()
        val controller = Robolectric.buildService(BalancedLocationService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(Intent(), 0, 1)
            val lm = shadowOf(app.getSystemService(Context.LOCATION_SERVICE) as LocationManager)
            val callback = lm.getLocationUpdateListeners("gps").single()
            val now = System.currentTimeMillis()
            val policy = org.robolectric.util.ReflectionHelpers.getField<BalancedLocationPolicy>(service, "policy")
            for (i in 0..4) {
                val time = now - 180_000 + i * 30_000
                policy.observe(time, LocationCandidate(23.13, 113.3, 5f, time, "gps"), null)
            }
            assertEquals(300_000L, policy.intervalMs)
            val move = Location("gps").apply {
                latitude = 23.131; longitude = 113.3; accuracy = 5f; time = now
                speed = 5f; speedAccuracyMetersPerSecond = .5f
            }
            callback.onLocationChanged(move)
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(6))
            FailingLocationManager.failRegistration = true
            callback.onLocationChanged(move)
            assertFalse("registration failure ends the session", BalancedLocationService.isRunning)
            val selector = org.robolectric.util.ReflectionHelpers.getField<LocationFixSelection>(service, "fixSelection")
            assertNull("the callback must not reopen a stopped window", selector.delayMs(android.os.SystemClock.elapsedRealtime()))
        } finally { FailingLocationManager.failRegistration = false; controller.destroy() }
    }

    @Test fun `late callback closes expired monotonic window without extending its deadline`() {
        PreferenceManager.getDefaultSharedPreferences(app).edit().putBoolean(CollectionConsent.KEY, true)
            .putBoolean(BalancedLocationService.KEY, true).commit()
        val controller = Robolectric.buildService(BalancedLocationService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(Intent(), 0, 1)
            val lm = shadowOf(app.getSystemService(Context.LOCATION_SERVICE) as LocationManager)
            val callback = lm.getLocationUpdateListeners("gps").single()
            fun fix(provider: String, accuracy: Float) = Location(provider).apply {
                latitude = 23.13; longitude = 113.3; this.accuracy = accuracy; time = System.currentTimeMillis()
            }
            callback.onLocationChanged(fix("network", 30f))
            // Advance monotonic time without dispatching the queued deadline (like a late wake).
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(6))
            callback.onLocationChanged(fix("gps", 5f))
            val policy = org.robolectric.util.ReflectionHelpers.getField<BalancedLocationPolicy>(service, "policy")
            val anchor = org.robolectric.util.ReflectionHelpers.getField<LocationCandidate>(policy, "anchor")
            assertEquals("expired window was flushed before accepting the new callback", "network", anchor.provider)
            val selector = org.robolectric.util.ReflectionHelpers.getField<LocationFixSelection>(service, "fixSelection")
            assertEquals(5_000L, selector.delayMs(android.os.SystemClock.elapsedRealtime()))
        } finally { controller.destroy() }
    }

    @Test fun `selection deadline survives repeat start but stop and permission loss clear it`() {
        for (revoke in listOf(false, true)) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(app)
            prefs.edit().putBoolean(CollectionConsent.KEY, true).putBoolean(BalancedLocationService.KEY, true).commit()
            val controller = Robolectric.buildService(BalancedLocationService::class.java).create()
            try {
                val service = controller.get()
                service.onStartCommand(Intent(), 0, 1)
                val lm = shadowOf(app.getSystemService(Context.LOCATION_SERVICE) as LocationManager)
                lm.getLocationUpdateListeners("gps").single().onLocationChanged(Location("network").apply {
                    latitude = 23.13; longitude = 113.3; accuracy = 25f; time = System.currentTimeMillis()
                })
                val selector = org.robolectric.util.ReflectionHelpers.getField<LocationFixSelection>(service, "fixSelection")
                val deadline = selector.delayMs(android.os.SystemClock.elapsedRealtime())
                assertEquals(5_000L, deadline)
                service.onStartCommand(Intent(), 0, 2)
                assertEquals(deadline, selector.delayMs(android.os.SystemClock.elapsedRealtime()))
                if (revoke) prefs.edit().putBoolean(CollectionConsent.KEY, false).commit()
                else service.onStartCommand(Intent().setAction(BalancedLocationService.ACTION_STOP), 0, 3)
                shadowOf(android.os.Looper.getMainLooper()).idle()
                assertNull(selector.delayMs(android.os.SystemClock.elapsedRealtime()))
                shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(6))
                assertFalse(BalancedLocationService.isRunning)
                assertTrue(lm.getLocationUpdateListeners().isEmpty())
            } finally { controller.destroy() }
        }
    }

    @Test fun `network first cannot leave sampling anchor different from selected precise gps`() {
        PreferenceManager.getDefaultSharedPreferences(app).edit().putBoolean(CollectionConsent.KEY, true)
            .putBoolean(BalancedLocationService.KEY, true).commit()
        val controller = Robolectric.buildService(BalancedLocationService::class.java).create()
        try {
            controller.get().onStartCommand(Intent(), 0, 1)
            val lm = shadowOf(app.getSystemService(Context.LOCATION_SERVICE) as LocationManager)
            val main = shadowOf(android.os.Looper.getMainLooper())
            val now = System.currentTimeMillis()
            val callback = lm.getLocationUpdateListeners("gps").single()
            for (provider in listOf("network", "gps")) {
                callback.onLocationChanged(Location(provider).apply {
                    latitude = 23.13; longitude = 113.3
                    accuracy = if (provider == "gps") 5f else 40f; time = now
                })
            }
            main.idleFor(java.time.Duration.ofSeconds(5))
            val policy = org.robolectric.util.ReflectionHelpers.getField<BalancedLocationPolicy>(controller.get(), "policy")
            val anchor = org.robolectric.util.ReflectionHelpers.getField<LocationCandidate>(policy, "anchor")
            assertEquals("sampling must use the same best fix as persistence", "gps", anchor.provider)
            assertEquals(30_000L, lm.getLegacyLocationRequests("gps").single().intervalMillis)
        } finally { controller.destroy() }
    }

    @Test fun `losing validated wifi immediately restores minute requests without duplicate subscriptions`() {
        PreferenceManager.getDefaultSharedPreferences(app).edit().putBoolean(CollectionConsent.KEY, true)
            .putBoolean(BalancedLocationService.KEY, true).commit()
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val before = shadowOf(cm).networkCallbacks.size
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
            lm.getLocationUpdateListeners(LocationManager.GPS_PROVIDER).single().onLocationChanged(Location("gps").apply {
                latitude = 23.13; longitude = 113.3; accuracy = 15f; time = now - 2_000
            })
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(5))
            assertEquals(300_000L, lm.getLegacyLocationRequests(LocationManager.GPS_PROVIDER).single().intervalMillis)
            network(true, validated = false)
            assertEquals(60_000L, lm.getLegacyLocationRequests(LocationManager.GPS_PROVIDER).single().intervalMillis)
            val listener = lm.getLocationUpdateListeners(LocationManager.GPS_PROVIDER).single()
            network(false)
            service.onStartCommand(Intent(), 0, 2)
            assertSame(listener, lm.getLocationUpdateListeners(LocationManager.GPS_PROVIDER).single())
            assertEquals(before + 1, shadowOf(cm).networkCallbacks.size)
            network(true)
            assertEquals(300_000L, lm.getLegacyLocationRequests(LocationManager.GPS_PROVIDER).single().intervalMillis)
        } finally { controller.destroy() }
        assertEquals(before, shadowOf(cm).networkCallbacks.size)
    }

    @Test fun `non wifi stationary session keeps one minute system requests`() = stationaryWithoutWifi(screenOn = true)

    @Test fun `screen off non wifi stationary session keeps one minute requests`() = stationaryWithoutWifi(screenOn = false)

    private fun stationaryWithoutWifi(screenOn: Boolean) {
        network(false)
        shadowOf(app.getSystemService(android.os.PowerManager::class.java)).setIsInteractive(screenOn)
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
            lm.getLocationUpdateListeners(LocationManager.GPS_PROVIDER).single().onLocationChanged(Location("gps").apply {
                latitude = 23.13; longitude = 113.3; accuracy = 15f; time = now - 2_000
            })
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(5))
            assertEquals(60_000L, lm.getLegacyLocationRequests(LocationManager.GPS_PROVIDER).single().intervalMillis)
        } finally { controller.destroy() }
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
                // Sampling policy now consumes the selected fix, not the first provider callback.
                shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(5))
        }
        assertEquals(30_000L, lm.getLegacyLocationRequests(LocationManager.GPS_PROVIDER).single().intervalMillis)
        // Android 12+ encodes passive requests with PASSIVE_INTERVAL; minUpdateInterval is the delivery throttle.
        fun passiveInterval() = if (android.os.Build.VERSION.SDK_INT >= 31)
            lm.getLocationRequests(LocationManager.PASSIVE_PROVIDER).single().minUpdateIntervalMillis
        else lm.getLegacyLocationRequests(LocationManager.PASSIVE_PROVIDER).single().intervalMillis
        assertEquals(30_000L, passiveInterval())
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
        assertEquals(30_000L, lm.getLegacyLocationRequests(LocationManager.GPS_PROVIDER).single().intervalMillis)
        assertEquals(30_000L, passiveInterval())
        service.onStartCommand(Intent().setAction(BalancedLocationService.ACTION_STOP), 0, 2)
        controller.destroy()
    }

    @Test fun `confirmation requests return to five minutes without another location callback`() = confirmationExpiry(wifiAfter = true)

    @Test fun `confirmation timeout after losing wifi returns to one minute without a location callback`() = confirmationExpiry(wifiAfter = false)

    private fun confirmationExpiry(wifiAfter: Boolean) {
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
                // Sampling policy now consumes the selected fix, not the first provider callback.
                shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(5))
            }
            sample(23.13, now - 2000)
            assertEquals(300_000L, lm.getLegacyLocationRequests(LocationManager.GPS_PROVIDER).single().intervalMillis)
            sample(23.14, now - 1000)
            assertEquals(30_000L, lm.getLegacyLocationRequests(LocationManager.GPS_PROVIDER).single().intervalMillis)
            if (!wifiAfter) network(false)
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(60))
            assertEquals(if (wifiAfter) 300_000L else 60_000L,
                lm.getLegacyLocationRequests(LocationManager.GPS_PROVIDER).single().intervalMillis)
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

    @org.robolectric.annotation.Implements(LocationManager::class)
    class FailingLocationManager : org.robolectric.shadows.ShadowLocationManager() {
        @org.robolectric.annotation.Implementation
        public override fun requestLocationUpdates(provider: String, minTime: Long, minDistance: Float,
            listener: android.location.LocationListener, looper: android.os.Looper?) {
            if (failRegistration) throw IllegalArgumentException("provider unavailable during registration")
            super.requestLocationUpdates(provider, minTime, minDistance, listener, looper)
        }
        companion object { @JvmField var failRegistration = false }
    }

}
