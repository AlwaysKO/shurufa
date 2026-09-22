package com.yuyan.imemodule.service.capture

import android.content.Context
import android.service.notification.NotificationListenerService
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.data.capture.model.ChatPlatform
import com.yuyan.imemodule.data.capture.ui.IntRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class NotificationScreenshotFallbackTest {
    @Test fun qqAndDouyinFallbackUseRealChatBoundsInsteadOfWholeAppPercentages() {
        for ((pkg, fixture) in listOf("com.tencent.mobileqq" to "qq-chat-9.3.60.json",
            "com.ss.android.ugc.aweme" to "douyin-chat-40.5.0.json")) {
            val snapshot = javaClass.getResourceAsStream("/capture/$fixture")!!.bufferedReader().use {
                kotlinx.serialization.json.Json.decodeFromString<com.yuyan.imemodule.data.capture.ui.UiNodeSnapshot>(it.readText())
            }
            val parsed = com.yuyan.imemodule.data.capture.adapter.AdapterRegistry.forPackage(pkg)!!.parse(snapshot)
                as com.yuyan.imemodule.data.capture.adapter.ParseResult.Success
            val selected = notificationChatViewport(pkg, snapshot)!!
            assertEquals(parsed.viewport.messages.single().mediaBounds, selected.bounds)
            assertEquals(parsed.viewport.messages.single().inputAreaBounds, selected.inputAreaBounds)
            org.junit.Assert.assertNull(notificationChatViewport(pkg, snapshot.copy(children = emptyList())))
        }
    }

    @Test fun qqFallbackIsPackageScopedAndKeepsNotificationEventIdentity() {
        val pkg = "com.tencent.mobileqq"
        assertTrue(shouldCaptureNotificationFallback(false, pkg, false, pkg))
        assertFalse(shouldCaptureNotificationFallback(false, "com.tencent.mm", false, pkg))
        assertFalse(shouldCaptureNotificationFallback(true, pkg, false, pkg))
        val descriptor = pendingNotificationScreenshotDescriptor(NotificationScreenshotFallbackRequest("qq-event", 1000, pkg))!!
        assertEquals(ChatPlatform.QQ, descriptor.platform)
        assertTrue(descriptor.externalKey.startsWith("notification-fallback-v2:"))
    }

    @org.junit.Test fun pendingFallbackGroupsOnlyOneNotificationEventNotAllUnknownPeers() {
        val event = NotificationScreenshotFallbackRequest("thread-a", 1000L, "com.tencent.mm")
        val first = pendingNotificationScreenshotDescriptor(event)!!
        org.junit.Assert.assertEquals(first.externalKey, pendingNotificationScreenshotDescriptor(event)!!.externalKey)
        org.junit.Assert.assertNotEquals(first.externalKey, pendingNotificationScreenshotDescriptor(event.copy(notificationKey = "thread-b"))!!.externalKey)
        org.junit.Assert.assertNotEquals(first.externalKey, pendingNotificationScreenshotDescriptor(event.copy(postedAtMillis = 2000L))!!.externalKey)
        org.junit.Assert.assertTrue(first.displayName.startsWith("待确认截图"))
    }

    @Test
    fun captureRequiresUnlockedWechatForegroundWithoutInputMethod() {
        assertTrue(shouldCaptureNotificationFallback(false, "com.tencent.mm", false))
        assertFalse(shouldCaptureNotificationFallback(true, "com.tencent.mm", false))
        assertFalse(shouldCaptureNotificationFallback(false, "com.tencent.mobileqq", false))
        assertFalse(shouldCaptureNotificationFallback(false, "com.tencent.mm", true))
    }

    @Test
    fun captureSupportsDouyinOnlyWhenDouyinIsForeground() {
        assertTrue(shouldCaptureNotificationFallback(
            screenLocked = false,
            foregroundPackage = "com.ss.android.ugc.aweme",
            inputMethodVisible = false,
            targetPackage = "com.ss.android.ugc.aweme",
        ))
        assertFalse(shouldCaptureNotificationFallback(
            screenLocked = false,
            foregroundPackage = "com.tencent.mm",
            inputMethodVisible = false,
            targetPackage = "com.ss.android.ugc.aweme",
        ))
    }

    @Test
    fun fallbackDescriptorKeepsDouyinScreenshotsSeparateFromWechat() {
        val douyin = notificationScreenshotFallbackDescriptor("com.ss.android.ugc.aweme")

        requireNotNull(douyin)
        assertEquals(ChatPlatform.DOUYIN, douyin.platform)
        assertEquals("douyin-hidden-notification", douyin.externalKey)
        assertEquals("抖音（截图兜底）", douyin.displayName)
        assertEquals("[抖音新消息截图]", douyin.messageText)
        assertEquals(null, notificationScreenshotFallbackDescriptor("com.example.unsupported"))
    }

    @Test
    fun fallbackUsesKnownSystemInsetWithoutGuessingAwayLastMessages() {
        assertEquals(
            IntRect(0, 30, 1000, 1000),
            notificationFallbackBounds(IntRect(0, 0, 1000, 1000), 30),
        )
    }

    @Test
    fun bridgeDeliversOnlyWhileAccessibilityServiceIsConnected() {
        val received = mutableListOf<NotificationScreenshotFallbackRequest>()
        val request = NotificationScreenshotFallbackRequest("notification", 123L)
        val connection = NotificationScreenshotFallbackBridge.connect { received += it }

        NotificationScreenshotFallbackBridge.request(request)
        connection.cancel()
        NotificationScreenshotFallbackBridge.request(request.copy(notificationKey = "later"))

        assertEquals(listOf(request), received)
    }

    @Test
    fun pendingRequestsAreDeduplicatedAndConsumedInArrivalOrder() {
        val queue = NotificationScreenshotFallbackQueue(clock = { 500L })
        val first = NotificationScreenshotFallbackRequest("first", 100L)
        val latest = NotificationScreenshotFallbackRequest("latest", 200L)

        queue.offer(first)
        queue.offer(latest)
        queue.offer(first)
        assertEquals(first, queue.peek())
        queue.removeIfSame(first)
        assertEquals(latest, queue.peek())
        queue.removeIfSame(latest)
        assertEquals(null, queue.peek())
    }

    @Test
    fun pendingRequestPersistsAcrossServiceInstancesAndExpires() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var now = 1_000L
        val first = NotificationScreenshotFallbackStore(context, clock = { now })
        val request = NotificationScreenshotFallbackRequest("persisted", 900L)
        val second = NotificationScreenshotFallbackRequest("second", 950L)
        first.offer(request)
        first.offer(second)

        assertEquals(request, NotificationScreenshotFallbackStore(context, clock = { now }).load())
        first.removeIfSame(request)
        assertEquals(second, NotificationScreenshotFallbackStore(context, clock = { now }).load())
        now += 10 * 60 * 1_000L + 1
        assertEquals(null, NotificationScreenshotFallbackStore(context, clock = { now }).load())
    }

    @Test
    fun hiddenNotificationWaitsForClickBeforeBecomingCaptureRequest() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val waiting = NotificationScreenshotFallbackStore(
            context,
            clock = { 1_000L },
            preferencesName = "waiting_click_test",
        )
        val request = NotificationScreenshotFallbackRequest("hidden", 900L)
        waiting.offerReplacingNotification(request)

        assertTrue(shouldArmNotificationScreenshot(NotificationListenerService.REASON_CLICK))
        assertFalse(shouldArmNotificationScreenshot(NotificationListenerService.REASON_CANCEL))
        assertEquals(request, waiting.takeByNotificationKey("hidden"))
        assertEquals(null, waiting.load())
    }

    @Test
    fun persistedRequestRetainsTargetPackage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = NotificationScreenshotFallbackStore(
            context,
            clock = { 1_000L },
            preferencesName = "douyin_package_roundtrip_test",
        )
        val request = NotificationScreenshotFallbackRequest(
            notificationKey = "douyin-hidden",
            postedAtMillis = 900L,
            packageName = "com.ss.android.ugc.aweme",
        )

        store.offer(request)

        assertEquals(request, NotificationScreenshotFallbackStore(
            context,
            clock = { 1_000L },
            preferencesName = "douyin_package_roundtrip_test",
        ).load())
        store.removeIfSame(request)
    }
}
