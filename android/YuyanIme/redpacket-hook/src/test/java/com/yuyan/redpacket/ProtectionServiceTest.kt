package com.yuyan.redpacket

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProtectionServiceTest {
    @Test fun realServiceDoesNotAllowUnknownScreenOffAndReleasesAfterKnownAppCooldown() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val power = shadowOf(context.getSystemService(PowerManager::class.java))
        power.setIsInteractive(false)
        val controller = Robolectric.buildService(ProtectionService::class.java).create()
        val service = controller.get()
        try {
            ProtectionService::class.java.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(service)
            ShadowSystemClock.advanceBy(Duration.ofSeconds(5))
            assertFalse(ProtectionService.allowed())
            power.setIsInteractive(true)
            val info = ApplicationInfo().apply { packageName = "test.foreground"; category = ApplicationInfo.CATEGORY_PRODUCTIVITY }
            shadowOf(context.packageManager).installPackage(android.content.pm.PackageInfo().apply { packageName = info.packageName; applicationInfo = info })
            val node = AccessibilityNodeInfo.obtain().apply { packageName = "test.foreground" }
            val window = AccessibilityWindowInfo.obtain()
            shadowOf(window).apply { setRoot(node); setActive(true); setType(AccessibilityWindowInfo.TYPE_APPLICATION) }
            shadowOf(service).setWindows(listOf(window))
            service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED))
            assertFalse(ProtectionService.allowed())
            ShadowSystemClock.advanceBy(Duration.ofSeconds(3))
            assertTrue(ProtectionService.allowed())
            val keyboard = AccessibilityWindowInfo.obtain()
            shadowOf(keyboard).setType(AccessibilityWindowInfo.TYPE_INPUT_METHOD)
            shadowOf(service).setWindows(listOf(window, keyboard))
            service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED))
            assertFalse(ProtectionService.allowed())
            power.setIsInteractive(false)
            shadowOf(service).setWindows(emptyList())
            assertFalse(ProtectionService.allowed())
        } finally { controller.destroy() }
        assertFalse(ProtectionService.allowed())
    }
}
