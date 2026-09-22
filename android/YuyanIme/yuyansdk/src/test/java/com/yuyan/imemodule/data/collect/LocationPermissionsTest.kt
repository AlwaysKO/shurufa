package com.yuyan.imemodule.data.collect

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class LocationPermissionsTest {
    private lateinit var context: Application

    @Before fun resetPermissions() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context).denyPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }

    @Test fun android12RequestIncludesBothPermissionsButNotBackground() {
        assertEquals(setOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            LocationPermissions.foregroundRequest().toSet())
    }

    @Test fun deniedPermissionDoesNotPermitLocation() {
        assertFalse(LocationPermissions.hasForegroundPermission(context))
    }

    @Test fun approximateGrantCanUseAvailableLocationProviders() {
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        assertTrue(LocationPermissions.hasForegroundPermission(context))
    }

    @Test fun preciseGrantPermitsLocation() {
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        assertTrue(LocationPermissions.hasForegroundPermission(context))
    }

    @Test fun permissionRevocationIsObservedWithoutRestart() {
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        assertTrue(LocationPermissions.hasForegroundPermission(context))
        shadowOf(context).denyPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        assertFalse(LocationPermissions.hasForegroundPermission(context))
    }
}
