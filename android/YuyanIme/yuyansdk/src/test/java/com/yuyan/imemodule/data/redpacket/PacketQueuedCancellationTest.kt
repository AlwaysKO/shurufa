package com.yuyan.imemodule.data.redpacket

import android.app.PendingIntent
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.yuyan.imemodule.data.collect.ImageUploadRuntime
import com.yuyan.imemodule.data.collect.resetImageInputForTest
import com.yuyan.imemodule.service.capture.PassiveChatAccessibilityService
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class PacketQueuedCancellationTest {
    private val assistant = GroupRedPacketAssistant
    private val service = Robolectric.buildService(PassiveChatAccessibilityService::class.java).get()
    private fun field(name: String) = assistant.javaClass.getDeclaredField(name).apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    private fun queue() = field("queued").get(assistant) as ArrayDeque<PacketRequest>

    @Before fun prepareBetweenPackets() {
        resetImageInputForTest()
        field("service").set(assistant, service)
        assistant.cancel("reset")
        PacketSettings.setEnabled(service, true)
        field("pausedUntil").setLong(assistant, 0L)
        field("ownBackIdleUntil").setLong(assistant, SystemClock.uptimeMillis() + 3500)
        queue().add(PacketRequest(PacketCandidate("next", "测试群", true),
            PendingIntent.getActivity(service, 0, Intent("test-packet"), PendingIntent.FLAG_IMMUTABLE),
            System.currentTimeMillis()))
    }

    @After fun cleanup() {
        assistant.cancel("reset")
        field("service").set(assistant, null)
        PacketSettings.setEnabled(service, false)
        resetImageInputForTest()
    }

    private fun event(type: Int, pkg: String = PACKET_WECHAT) {
        val event = AccessibilityEvent.obtain(type).apply { packageName = pkg }
        try { assistant.event(event) } finally { event.recycle() }
    }

    @Test fun touchDuringReturnCooldownCancelsNextPacket() {
        event(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START)
        assertTrue(queue().isEmpty())
    }

    @Test fun scrollDuringReturnCooldownCancelsNextPacket() {
        event(AccessibilityEvent.TYPE_VIEW_SCROLLED)
        assertTrue(queue().isEmpty())
    }

    @Test fun switchingAppsDuringReturnCooldownCancelsEvenBeforeNextFlowStarts() {
        field("enteredWechat").setBoolean(assistant, false)
        event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "other.app")
        assertTrue(queue().isEmpty())
    }

    @Test fun inputStillBusyAfterReturnCooldownClearsPendingPackets() {
        field("ownBackIdleUntil").setLong(assistant, 0L)
        ImageUploadRuntime.noteKeyActivity()
        assistant.javaClass.getDeclaredMethod("process").apply { isAccessible = true }.invoke(assistant)
        assertTrue(queue().isEmpty())
    }
}
