package com.yuyan.imemodule.data.collect

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class DataCollectorNetworkTypeTest {
    @Test
    fun missingNetworkStatePermissionDoesNotCrashInput() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).denyPermissions(Manifest.permission.ACCESS_NETWORK_STATE)
        val method = DataCollector::class.java.getDeclaredMethod("networkType", Context::class.java).apply {
            isAccessible = true
        }

        assertNull(method.invoke(DataCollector, context))
    }
}
