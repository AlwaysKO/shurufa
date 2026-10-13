package com.yuyan.imemodule.data.collect

import android.accessibilityservice.AccessibilityService
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.yuyan.imemodule.data.redpacket.GroupRedPacketAssistant
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@LooperMode(LooperMode.Mode.PAUSED)
class GameForegroundUnknownEventTest {
    class TestService : AccessibilityService() {
        override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
        override fun onInterrupt() = Unit
    }

    private var service: TestService? = null

    @After fun cleanUp() {
        service?.let(GameForegroundMonitor::disconnect)
        shadowOf(Looper.getMainLooper()).idle()
        GameWorkRuntime.setGaming(false)
        val gate = GameWorkRuntime::class.java.getDeclaredField("gate").apply { isAccessible = true }.get(GameWorkRuntime)
        gate.javaClass.getDeclaredField("resumeAt").apply { isAccessible = true }.setLong(gate, 0)
        GroupRedPacketAssistant::class.java.getDeclaredField("gameProtected").apply { isAccessible = true }
            .setBoolean(GroupRedPacketAssistant, false)
        GroupRedPacketAssistant::class.java.getDeclaredField("gameResumeAt").apply { isAccessible = true }
            .setLong(GroupRedPacketAssistant, 0)
    }

    private fun assertUnknownEventRevokesVerification(packageName: String?) {
        val attached = Robolectric.buildService(TestService::class.java).get()
        service = attached
        GameForegroundMonitor.connect(attached)
        shadowOf(Looper.getMainLooper()).idle()
        val session = GameForegroundMonitor::class.java.getDeclaredField("session").apply { isAccessible = true }
            .get(GameForegroundMonitor)!!
        val state = session.javaClass.getDeclaredField("state").apply { isAccessible = true }
            .get(session) as GameForegroundState
        val token = state.connect(true)
        state.initialize(token, "known.game", GameForegroundResult(GameForegroundWindow.Application, true, "known.game"))
        val verified = GameForegroundState::class.java.getDeclaredField("foregroundVerified").apply { isAccessible = true }
        assertEquals(true, verified.get(state))
        @Suppress("DEPRECATION")
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
        try {
            event.packageName = packageName
            GameForegroundMonitor.event(event)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(false, verified.get(state))
        } finally { @Suppress("DEPRECATION") event.recycle() }
    }

    @Test fun absentPackageOnRealMonitorEventRevokesPreviouslyVerifiedGame() {
        assertUnknownEventRevokesVerification(null)
    }

    @Test fun emptyPackageOnRealMonitorEventRevokesPreviouslyVerifiedGame() {
        assertUnknownEventRevokesVerification("")
    }
}
