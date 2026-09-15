package com.yuyan.imemodule.service.capture

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
    @Test
    fun captureRequiresUnlockedWechatForegroundWithoutInputMethod() {
        assertTrue(shouldCaptureNotificationFallback(false, "com.tencent.mm", false))
        assertFalse(shouldCaptureNotificationFallback(true, "com.tencent.mm", false))
        assertFalse(shouldCaptureNotificationFallback(false, "com.tencent.mobileqq", false))
        assertFalse(shouldCaptureNotificationFallback(false, "com.tencent.mm", true))
    }

    @Test
    fun captureBoundsExcludeStatusAndComposerAreas() {
        assertEquals(
            IntRect(0, 30, 1000, 900),
            notificationFallbackBounds(IntRect(0, 0, 1000, 1000)),
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
}
