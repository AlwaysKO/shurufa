package com.yuyan.imemodule.data.redpacket

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.BroadcastReceiver
import android.os.Looper
import android.os.SystemClock
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import android.view.accessibility.AccessibilityEvent
import com.yuyan.imemodule.service.capture.PassiveChatAccessibilityService
import java.time.Duration
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class PacketGameProtectionTest {
    private val assistant = GroupRedPacketAssistant
    private val service = Robolectric.buildService(PassiveChatAccessibilityService::class.java).get()
    private fun field(name: String) = assistant.javaClass.getDeclaredField(name).apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST") private fun queue() = field("queued").get(assistant) as ArrayDeque<PacketRequest>
    private fun invoke(name: String) = assistant.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(assistant)
    private fun request(id: Int = 1) = PacketRequest(PacketCandidate("packet-$id", "测试群", true),
        PendingIntent.getActivity(service, id, Intent("test-packet-$id"), PendingIntent.FLAG_IMMUTABLE), System.currentTimeMillis())

    @Before fun prepare() {
        field("service").set(assistant, service)
        assistant.cancel("reset")
        assistant.protectForeground(false)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(4))
        field("pausedUntil").setLong(assistant, 0)
        field("ownBackIdleUntil").setLong(assistant, 0)
        PacketSettings.setEnabled(service, true)
    }
    @After fun cleanup() {
        assistant.cancel("reset")
        PacketSettings.setEnabled(service, false)
        field("service").set(assistant, null)
        assistant.protectForeground(false)
        // Robolectric会重置时钟，但保留Kotlin singleton。
        runCatching { field("gameResumeAt").setLong(assistant, 0) }
    }

    @Test fun gameBlocksNotificationLaunchAndKeepsQueuedRequest() {
        assistant.protectForeground(true)
        queue().add(request())
        invoke("startNext")
        assertNull(shadowOf(service).nextStartedActivity)
        assertNull(field("flow").get(assistant))
        assertEquals(1, queue().size)
        assertFalse(assistant.isInteractionAllowed())
        assertFalse(field("tickScheduled").getBoolean(assistant))
    }

    @Test fun gameInvalidatesWakeAndActiveFlowWithoutNavigatingOrLocking() {
        val next = request()
        field("request").set(assistant, next)
        field("flow").set(assistant, PacketFlow(next.candidate, SystemClock.uptimeMillis()))
        field("wakeToken").set(assistant, "old-wake")
        field("wokeScreen").setBoolean(assistant, true)
        assistant.protectForeground(true)
        assertFalse(assistant.hasWakeRequest("old-wake"))
        assistant.wakeReady("old-wake")
        invoke("finishSession")
        assertNull(field("flow").get(assistant))
        assertEquals(1, queue().size)
        assertTrue(shadowOf(service).globalActionsPerformed.isEmpty())
        assertNull(shadowOf(service).nextStartedActivity)
    }

    @Test fun gameTouchesDoNotDiscardDeferredNotifications() {
        assistant.protectForeground(true)
        queue().add(request())
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START).apply {
            packageName = "com.tencent.tmgp.sgame"
        }
        try { assistant.event(event) } finally { event.recycle() }
        assertEquals(1, queue().size)
        assertFalse(field("tickScheduled").getBoolean(assistant))
    }

    @Test fun freshNotificationResumesOnlyAfterStableNonGameCooldown() {
        assistant.protectForeground(true)
        queue().add(request())
        assistant.protectForeground(false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2999))
        assertNull(shadowOf(service).nextStartedActivity)
        assertFalse(assistant.isInteractionAllowed())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertNotNull(shadowOf(service).nextStartedActivity)
    }

    @Test fun oldNotificationIsNotReplayedAfterLongGame() {
        assistant.protectForeground(true)
        queue().add(request().copy(postedAt = System.currentTimeMillis() - 16_000))
        ShadowSystemClock.advanceBy(Duration.ofSeconds(16))
        assistant.protectForeground(false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertTrue(queue().isEmpty())
        assertNull(shadowOf(service).nextStartedActivity)
    }

    @Test fun notificationReceivedDuringGameSurvivesLongGame() {
        assistant.protectForeground(true)
        postNotification(201)
        val original = queue().removeFirst()
        queue().add(original.copy(postedAt = System.currentTimeMillis() - 60 * 60_000))
        assistant.protectForeground(false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals("test-packet-201", shadowOf(service).nextStartedActivity?.action)
    }

    @Test fun deferredNotificationSurvivesAnotherGameProtectionTransition() {
        assistant.protectForeground(true)
        postNotification(202)
        val original = queue().removeFirst()
        queue().add(original.copy(postedAt = System.currentTimeMillis() - 60 * 60_000))
        assistant.protectForeground(false)
        assistant.protectForeground(true)
        assertEquals(1, queue().size)
        assistant.protectForeground(false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertNotNull(shadowOf(service).nextStartedActivity)
    }

    @Test fun returningToLauncherDuringResumeCooldownKeepsDeferredPacket() {
        assistant.protectForeground(true)
        postNotification(203)
        assistant.protectForeground(false)
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            packageName = "com.hihonor.android.launcher"
        }
        try { assistant.event(event) } finally { event.recycle() }
        assertEquals(1, queue().size)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertNotNull(shadowOf(service).nextStartedActivity)
    }

    @Test fun touchingDesktopDuringCooldownStillCancelsDeferredPackets() {
        assistant.protectForeground(true)
        postNotification(204)
        assistant.protectForeground(false)
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START)
        try { assistant.event(event) } finally { event.recycle() }
        assertTrue(queue().isEmpty())
    }

    @Test fun deferredPacketOlderThanOneDayIsNotReplayedOrRefreshedByGameTransition() {
        assistant.protectForeground(true)
        postNotification(205)
        val original = queue().removeFirst()
        queue().add(original.copy(postedAt = System.currentTimeMillis() - 24 * 60 * 60_000L - 1))
        assistant.protectForeground(false)
        assistant.protectForeground(true)
        assistant.protectForeground(false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertTrue(queue().isEmpty())
        assertNull(shadowOf(service).nextStartedActivity)
    }

    @Test fun cancelledNotificationEntryDoesNotDiscardNextDeferredPacket() {
        assistant.protectForeground(true)
        postNotification(206)
        postNotification(207)
        queue().first().intent.cancel()
        assistant.protectForeground(false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3500))
        assertEquals("test-packet-207", shadowOf(service).nextStartedActivity?.action)
    }

    private fun postNotification(id: Int) {
        val next = request(id)
        val notification = Notification.Builder(service, "test").setContentTitle("测试群")
            .setContentText("小明: [微信红包]恭喜发财").setContentIntent(next.intent).build()
        assistant.notification(StatusBarNotification(PACKET_WECHAT, PACKET_WECHAT, id, null, 0, 0, 0,
            notification, UserHandle.getUserHandleForUid(0), System.currentTimeMillis()))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun reenteringGameInvalidatesScheduledResume() {
        assistant.protectForeground(true)
        queue().add(request())
        assistant.protectForeground(false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assistant.protectForeground(true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertNull(shadowOf(service).nextStartedActivity)
        assertEquals(1, queue().size)
        assertFalse(field("tickScheduled").getBoolean(assistant))
    }

    @Test fun screenOffDuringGameKeepsDeferredNotificationWithoutWakingScreen() {
        assistant.protectForeground(true)
        queue().add(request())
        (field("screenReceiver").get(assistant) as BroadcastReceiver)
            .onReceive(service, Intent(Intent.ACTION_SCREEN_OFF))
        assertEquals(1, queue().size)
        assertFalse(assistant.isInteractionAllowed())
        assertFalse(field("tickScheduled").getBoolean(assistant))
    }

    @Test fun oldReadCompletionReschedulesDeferredWorkAfterResumeTickWasBusy() {
        assistant.protectForeground(true)
        queue().add(request())
        field("reading").setBoolean(assistant, true)
        try {
            assistant.protectForeground(false)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
            assertNull(shadowOf(service).nextStartedActivity)
            invoke("finishReading")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
            assertNotNull(shadowOf(service).nextStartedActivity)
        } finally { field("reading").setBoolean(assistant, false) }
    }

    @Test fun requestWhoseCardWasAlreadyClickedIsNotReplayedOnGamePause() {
        val next = request()
        field("request").set(assistant, next)
        field("flow").set(assistant, PacketFlow(next.candidate, SystemClock.uptimeMillis()))
        field("clickedCard").set(assistant, "already-clicked")
        assistant.protectForeground(true)
        assertTrue(queue().isEmpty())
        assertNull(field("flow").get(assistant))
    }

    @Test fun burstDuringGameHasAtMostFourDeferredNoticesAndDoesNotStartTicks() {
        assistant.protectForeground(true)
        repeat(6) { id ->
            val next = request(id + 30)
            val notification = Notification.Builder(service, "test").setContentTitle("测试群")
                .setContentText("小明: [微信红包]恭喜发财").setContentIntent(next.intent).build()
            assistant.notification(StatusBarNotification(PACKET_WECHAT, PACKET_WECHAT, id + 30, null, 0, 0, 0,
                notification, UserHandle.getUserHandleForUid(0), System.currentTimeMillis()))
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(4, queue().size)
        assertNull(shadowOf(service).nextStartedActivity)
        assertFalse(field("tickScheduled").getBoolean(assistant))
    }
}
