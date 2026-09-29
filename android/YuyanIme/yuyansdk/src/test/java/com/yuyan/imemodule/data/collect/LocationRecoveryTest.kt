package com.yuyan.imemodule.data.collect

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.ComponentName
import android.content.Intent
import android.location.LocationManager
import android.os.Build
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
class LocationRecoveryTest {
    private lateinit var app: Application
    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(app).edit().clear()
            .putBoolean(CollectionConsent.KEY, true).putBoolean(BalancedLocationService.KEY, true).commit()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        shadowOf(app.getSystemService(Context.LOCATION_SERVICE) as LocationManager)
            .setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        while (shadowOf(app).nextStartedService != null) { }
    }

    @Test fun `boot update and location enabled broadcasts restore existing choice`() {
        for (action in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                LocationManager.MODE_CHANGED_ACTION)) {
            LocationRecoveryReceiver().onReceive(app, Intent(action))
            assertEquals(BalancedLocationService::class.java.name, shadowOf(app).nextStartedService?.component?.className)
            assertTrue(BalancedLocationService.isEnabled(app))
        }
    }

    @Test fun `unrelated and locked boot broadcasts do not read or start recording`() {
        for (action in listOf("unrelated", "android.intent.action.LOCKED_BOOT_COMPLETED")) {
            LocationRecoveryReceiver().onReceive(app, Intent(action))
            assertNull(shadowOf(app).nextStartedService)
        }
    }

    @Test fun `user stop or disabled consent cannot be revived`() {
        BalancedLocationService.stop(app)
        LocationRecoveryReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(shadowOf(app).nextStartedService)
        assertFalse(BalancedLocationService.isEnabled(app))
        val prefs = PreferenceManager.getDefaultSharedPreferences(app)
        prefs.edit().putBoolean(BalancedLocationService.KEY, true).putBoolean(CollectionConsent.KEY, false).commit()
        assertFalse(BalancedLocationService.restore(app))
        assertNull(shadowOf(app).nextStartedService)
        prefs.edit().putBoolean(CollectionConsent.KEY, true).putBoolean("location_tracking_enable", false).commit()
        assertFalse(BalancedLocationService.restore(app))
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun `background permission is required after Android 10 but foreground activity can restore`() {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        assertEquals(Build.VERSION.SDK_INT < 29, BalancedLocationService.restore(app))
        if (Build.VERSION.SDK_INT < 29) assertNotNull(shadowOf(app).nextStartedService)
        else assertNull(shadowOf(app).nextStartedService)
        assertTrue(BalancedLocationService.isEnabled(app))
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            assertTrue(BalancedLocationService.restoreFromActivity(activity.get()))
            assertNotNull(shadowOf(app).nextStartedService)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun `platform rejection preserves preference and never opens an activity`() {
        val denied = object : ContextWrapper(app) {
            override fun startService(service: Intent): ComponentName? = throw SecurityException("system denied")
            override fun startForegroundService(service: Intent): ComponentName? = throw IllegalStateException("background denied")
        }
        assertFalse(BalancedLocationService.restore(denied))
        assertTrue(BalancedLocationService.isEnabled(app))
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun `missing location permissions and system location off only pause`() {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        assertFalse(BalancedLocationService.restore(app))
        assertTrue(BalancedLocationService.isEnabled(app))
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        val manager = shadowOf(app.getSystemService(Context.LOCATION_SERVICE) as LocationManager)
        manager.setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        manager.setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)
        if (Build.VERSION.SDK_INT >= 28) manager.setLocationEnabled(false)
        assertFalse(BalancedLocationService.restore(app))
        assertTrue(BalancedLocationService.isEnabled(app))
        assertNull(shadowOf(app).nextStartedService)
    }
}
