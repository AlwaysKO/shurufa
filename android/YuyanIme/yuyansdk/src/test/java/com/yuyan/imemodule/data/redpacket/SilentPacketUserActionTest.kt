package com.yuyan.imemodule.data.redpacket

import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SilentPacketUserActionTest {
    @Test fun touchAndTextChangesRemainUserActions() {
        assertTrue(silentPacketUserAction(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START))
        assertTrue(silentPacketUserAction(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED))
    }

    @Test fun mainScreenClickStopsSilentWork() {
        assertTrue(silentPacketUserAction(AccessibilityEvent.TYPE_VIEW_CLICKED))
    }

    @Test fun mainScreenScrollStopsSilentWork() {
        assertTrue(silentPacketUserAction(AccessibilityEvent.TYPE_VIEW_SCROLLED))
    }

    @Test fun passiveWindowUpdatesAreNotUserActions() {
        assertFalse(silentPacketUserAction(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))
        assertFalse(silentPacketUserAction(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED))
        assertFalse(silentPacketUserAction(AccessibilityEvent.TYPE_WINDOWS_CHANGED))
        assertFalse(silentPacketUserAction(AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED))
        assertFalse(silentPacketUserAction(0))
    }
}
