package com.yuyan.imemodule.data.capture.media

import org.junit.Assert.*
import org.junit.Test

class ScreenshotWindowGuardTest {
    @Test fun globalScreenshotMustNotBeAcceptedAfterSwitchingToAnotherApp() {
        assertFalse(canUseScreenshotResult(windowScoped = false, requestedWindowId = 10, activeWindowId = 20))
        assertFalse(canUseScreenshotResult(windowScoped = false, requestedWindowId = 10, activeWindowId = null))
        assertTrue(canUseScreenshotResult(windowScoped = false, requestedWindowId = 10, activeWindowId = 10))
    }
    @Test fun windowScopedScreenshotCanKeepTheOriginalFrameAfterLeavingChat() {
        assertTrue(canUseScreenshotResult(windowScoped = true, requestedWindowId = 10, activeWindowId = 20))
    }
}
