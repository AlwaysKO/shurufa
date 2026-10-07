package com.yuyan.imemodule.data.collect

import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class GameForegroundWindowTest {
    @Test fun negativeEventIdUsesVerifiedActiveApplicationPackage() {
        withWindows(window(44, "reader", active = true)) { windows ->
            val result = inspectGameForegroundWindow("reader", -1, windows)
            assertEquals(GameForegroundWindow.Application, result.window)
            assertEquals(44, result.resolvedWindowId)
        }
    }

    @Test fun vanishedHomePluginWindowResolvesActualLauncherIdentity() {
        withWindows(window(5053, "com.hihonor.android.launcher", active = true, focused = true)) { windows ->
            val result = inspectGameForegroundWindow("com.hihonor.hiboard", 5063, windows)
            assertEquals(GameForegroundWindow.Application, result.window)
            assertEquals(5053, result.resolvedWindowId)
            assertEquals("com.hihonor.android.launcher", result.verifiedPackage)
        }
    }

    @Test fun obsoleteEventIdUsesReplacementActiveWindowFromSamePackage() {
        withWindows(window(3, "reader"), window(44, "reader", active = true)) { windows ->
            val result = inspectGameForegroundWindow("reader", 3, windows)
            assertEquals(GameForegroundWindow.Application, result.window)
            assertEquals(44, result.resolvedWindowId)
        }
    }

    @Test fun focusedApplicationCanConfirmWhenNoApplicationIsActive() {
        withWindows(window(44, "reader", focused = true)) { windows ->
            assertEquals(GameForegroundWindow.Application, inspectGameForegroundWindow("reader", -1, windows).window)
        }
    }

    @Test fun obsoleteEventPackageReportsActualGameIdentityEvenWhenWindowIdMatches() {
        for (eventId in listOf(-1, 44)) {
            withWindows(window(44, "actual.game", active = true)) { windows ->
                val result = inspectGameForegroundWindow("reader", eventId, windows)
                assertEquals(GameForegroundWindow.Application, result.window)
                assertEquals("actual.game", result.verifiedPackage)
            }
        }
    }

    @Test fun actualActiveGameOverridesFocusedBackgroundReaderEvidence() {
        for (eventId in listOf(-1, 45)) {
            withWindows(window(44, "actual.game", active = true), window(45, "reader", focused = true)) { windows ->
                val result = inspectGameForegroundWindow("reader", eventId, windows)
                assertEquals(GameForegroundWindow.Application, result.window)
                assertEquals("actual.game", result.verifiedPackage)
            }
        }
    }

    @Test fun overlayDoesNotCountAsVerifiedForegroundApplication() {
        withWindows(window(44, "reader", active = true, type = AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY)) { windows ->
            assertEquals(GameForegroundWindow.Overlay, inspectGameForegroundWindow("reader", 44, windows).window)
            assertEquals(GameForegroundWindow.Unknown, inspectGameForegroundWindow("reader", -1, windows).window)
        }
    }

    @Test fun missingRootAndAmbiguousActiveWindowsRemainUnknown() {
        withWindows(window(44, null, active = true)) { windows ->
            assertEquals(GameForegroundWindow.Unknown, inspectGameForegroundWindow("reader", 44, windows).window)
        }
        withWindows(window(44, "reader", active = true), window(45, "actual.game", active = true)) { windows ->
            assertEquals(GameForegroundWindow.Unknown, inspectGameForegroundWindow("reader", -1, windows).window)
        }
    }

    @Suppress("DEPRECATION")
    private fun window(id: Int, packageName: String?, active: Boolean = false, focused: Boolean = false,
                       type: Int = AccessibilityWindowInfo.TYPE_APPLICATION): AccessibilityWindowInfo =
        AccessibilityWindowInfo.obtain().also { window ->
            Shadows.shadowOf(window).apply {
                setId(id); setType(type); setActive(active); setFocused(focused)
                if (packageName != null) setRoot(AccessibilityNodeInfo.obtain().apply { this.packageName = packageName })
            }
        }

    @Suppress("DEPRECATION")
    private fun withWindows(vararg windows: AccessibilityWindowInfo, work: (List<AccessibilityWindowInfo>) -> Unit) {
        try { work(windows.toList()) } finally { windows.forEach { it.recycle() } }
    }
}
