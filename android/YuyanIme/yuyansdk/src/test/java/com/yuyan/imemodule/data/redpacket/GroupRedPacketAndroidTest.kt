package com.yuyan.imemodule.data.redpacket

import android.app.Notification
import android.app.PendingIntent
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.UserHandle
import android.provider.Settings
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.shadows.ShadowNotificationListenerService
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import com.yuyan.imemodule.service.capture.PassiveChatAccessibilityService
import com.yuyan.imemodule.service.capture.PassiveNotificationListener

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 30, 31, 35])
class GroupRedPacketAndroidTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @Before fun reset() {
        PacketSettings.setEnabled(context, false)
        PacketServiceState.accessibilityConnected = false
        PacketServiceState.notificationConnected = false
    }

    @Test fun featureDefaultsOffAndIsIndependentOfCollectionConsent() {
        assertFalse(PacketSettings.enabled(context))
        PacketSettings.setEnabled(context, true)
        assertTrue(PacketSettings.enabled(context))
        PacketSettings.setEnabled(context, false)
        assertFalse(PacketSettings.enabled(context))
    }
    @Test fun legacyNotificationKeepsUnknownGroupUntilPageVerification() {
        val notification = Notification.Builder(context, "chat")
            .setContentTitle("同学群").setContentText("小明: [微信红包]恭喜发财")
            .setContentIntent(PendingIntent.getActivity(context, 0, Intent("open-chat"), PendingIntent.FLAG_IMMUTABLE))
            .build()
        val sbn = StatusBarNotification("com.tencent.mm", "com.tencent.mm", 1, null, 0, 0, 0,
            notification, UserHandle.getUserHandleForUid(0), 1_000)
        val request = packetNotification(sbn, 1_100)!!
        assertFalse(request.candidate.confirmedGroup)
        assertEquals("同学群", request.candidate.chatName)
    }
    @Test fun summaryAndMissingContentIntentAreNotOpened() {
        fun sbn(n: Notification) = StatusBarNotification("com.tencent.mm", "com.tencent.mm", 1, null,
            0, 0, 0, n, UserHandle.getUserHandleForUid(0), 1_000)
        val builder = Notification.Builder(context, "chat").setContentTitle("同学群")
            .setContentText("小明: [微信红包]恭喜发财")
        assertNull(packetNotification(sbn(builder.build()), 1_100))
        builder.setContentIntent(PendingIntent.getActivity(context, 0, Intent("open-chat"), PendingIntent.FLAG_IMMUTABLE))
            .setGroup("messages").setGroupSummary(true)
        assertNull(packetNotification(sbn(builder.build()), 1_100))
    }
    @Test fun wakeActivityCannotStartWorkWithoutCurrentRequest() {
        assertFalse(GroupRedPacketAssistant.hasWakeRequest("made-up-token"))
    }

    private fun notification(text: String? = null): Notification = Notification.Builder(context, "chat")
        .setContentTitle("同学群").setContentText(text)
        .setContentIntent(PendingIntent.getActivity(context, 0, Intent("open-chat"), PendingIntent.FLAG_IMMUTABLE))
        .build()

    private fun parse(notification: Notification): PacketRequest? = packetNotification(
        StatusBarNotification("com.tencent.mm", "com.tencent.mm", 1, null, 0, 0, 0,
            notification, UserHandle.getUserHandleForUid(0), 1_000), 1_100)

    @Test fun emptyTextFallsBackToNonblankBigText() {
        val notification = notification("  ")
        notification.extras.putCharSequence(Notification.EXTRA_BIG_TEXT, "小明: [微信红包]恭喜发财")
        assertEquals("同学群", parse(notification)?.candidate?.chatName)
    }

    @Test fun emptyTextAndBigTextFallBackToLatestNonblankLine() {
        val notification = notification("")
        notification.extras.putCharSequence(Notification.EXTRA_BIG_TEXT, "  ")
        notification.extras.putCharSequenceArray(Notification.EXTRA_TEXT_LINES,
            arrayOf("小明: 你好", "小明: [微信红包]恭喜发财", " "))
        assertNotNull(parse(notification))
    }

    @Test fun normalLatestLineDoesNotReplayEarlierPacket() {
        val notification = notification()
        notification.extras.putCharSequenceArray(Notification.EXTRA_TEXT_LINES,
            arrayOf("小明: [微信红包]恭喜发财", "小明: 你好"))
        assertNull(parse(notification))
    }

    @Test fun currentNormalTextDoesNotFallBackToOldPacket() {
        val notification = notification("小明: 你好")
        notification.extras.putCharSequence(Notification.EXTRA_BIG_TEXT, "小明: [微信红包]恭喜发财")
        assertNull(parse(notification))
    }

    private fun grantPermissions() {
        Settings.Secure.putString(context.contentResolver, "enabled_notification_listeners",
            ComponentName(context, PassiveNotificationListener::class.java).flattenToString())
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ComponentName(context, PassiveChatAccessibilityService::class.java).flattenToString())
        PacketSettings.setEnabled(context, true)
    }

    @Test fun permissionsWithoutBindingShowWaitingForConnection() {
        grantPermissions()
        assertEquals("已开启，等待通知服务连接", PacketSettings.status(context))
    }

    @Test @Config(sdk = [28]) fun lowRamAndroidNineExplainsNotificationLimitation() {
        grantPermissions()
        shadowOf(context.getSystemService(ActivityManager::class.java)).setIsLowRamDevice(true)
        assertEquals("当前低内存设备系统不支持通知监听，无法通过通知触发", PacketSettings.status(context))
    }

    @Test @Config(sdk = [30]) fun lowRamAndroidElevenDoesNotUseLegacyLimitation() {
        grantPermissions()
        shadowOf(context.getSystemService(ActivityManager::class.java)).setIsLowRamDevice(true)
        assertEquals("已开启，等待通知服务连接", PacketSettings.status(context))
    }

    @Test fun notificationBindingIsReportedSeparatelyFromAccessibilityBinding() {
        grantPermissions()
        val listener = Robolectric.buildService(PassiveNotificationListener::class.java).get()
        listener.onListenerConnected()
        assertEquals("已开启，等待无障碍服务连接", PacketSettings.status(context))
        listener.onListenerDisconnected()
        assertEquals("已开启，等待通知服务连接", PacketSettings.status(context))
    }

    @Test fun disconnectedListenerRequestsOnlyOneRebindUntilConnectedAgain() {
        grantPermissions()
        val listener = Robolectric.buildService(PassiveNotificationListener::class.java).get()
        val before = ShadowNotificationListenerService.getRebindRequestCount()
        listener.onListenerDisconnected()
        listener.onListenerDisconnected()
        assertEquals(before + 1, ShadowNotificationListenerService.getRebindRequestCount())
        listener.onListenerConnected()
        listener.onListenerDisconnected()
        assertEquals(before + 2, ShadowNotificationListenerService.getRebindRequestCount())
    }

    @Test fun disabledFeatureDoesNotRequestNotificationRebind() {
        grantPermissions()
        PacketSettings.setEnabled(context, false)
        val listener = Robolectric.buildService(PassiveNotificationListener::class.java).get()
        val before = ShadowNotificationListenerService.getRebindRequestCount()
        listener.onListenerDisconnected()
        assertEquals(before, ShadowNotificationListenerService.getRebindRequestCount())
    }

    @Test fun revokedPermissionDoesNotRequestNotificationRebind() {
        grantPermissions()
        Settings.Secure.putString(context.contentResolver, "enabled_notification_listeners", "")
        val listener = Robolectric.buildService(PassiveNotificationListener::class.java).get()
        val before = ShadowNotificationListenerService.getRebindRequestCount()
        listener.onListenerDisconnected()
        assertEquals(before, ShadowNotificationListenerService.getRebindRequestCount())
    }
}
