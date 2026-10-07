package com.yuyan.imemodule.service.capture

import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundChatCaptureBridgeTest {
    @Test
    fun typingInChatInputDoesNotTriggerScreenshotBeforeSend() {
        assertFalse(shouldCaptureForegroundChatEvent(
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            "android.widget.EditText",
        ))
        assertTrue(shouldCaptureForegroundChatEvent(
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            "android.widget.TextView",
        ))
        assertTrue(shouldCaptureForegroundChatEvent(
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            "android.widget.EditText",
        ))
        assertTrue(shouldCaptureForegroundChatEvent(
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            "androidx.recyclerview.widget.RecyclerView",
        ))
        assertFalse(shouldCaptureForegroundChatEvent(
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            "android.widget.Button",
            visibleText = "播放视频",
        ))
        assertTrue(shouldCaptureForegroundChatEvent(
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            "android.widget.Button",
            visibleText = "发送",
        ))
        assertTrue(shouldCaptureForegroundChatEvent(
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            "android.widget.TextView",
            visibleText = "转文字",
        ))
    }

    @Test
    fun openingChatAfterReadingIncomingMessageTriggersCaptureWithoutReply() {
        assertTrue(shouldCaptureForegroundChatEvent(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            "android.widget.FrameLayout",
        ))
        assertTrue(shouldCaptureForegroundChatEvent(
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            "androidx.recyclerview.widget.RecyclerView",
        ))
    }

    @Test
    fun supportedChatPackagesRequireRegisteredPageAdapters() {
        assertTrue(isForegroundChatCapturePackage("com.tencent.mm"))
        assertTrue(isForegroundChatCapturePackage("com.tencent.mobileqq"))
        assertTrue(isForegroundChatCapturePackage("com.ss.android.ugc.aweme"))
        assertFalse(isForegroundChatCapturePackage("com.example.other"))
        assertFalse(isForegroundChatCapturePackage(null))
    }

    @Test
    fun connectedAccessibilityServiceReceivesOutgoingSendRequest() {
        val received = mutableListOf<ForegroundChatCaptureRequest>()
        val connection = ForegroundChatCaptureBridge.connect { received += it }

        ForegroundChatCaptureBridge.request("com.tencent.mm", requestedAtMillis = 123L)
        ForegroundChatCaptureBridge.request("com.example.other", requestedAtMillis = 456L)
        connection.cancel()
        ForegroundChatCaptureBridge.request("com.tencent.mm", requestedAtMillis = 789L)

        assertEquals(
            listOf(ForegroundChatCaptureRequest("com.tencent.mm", 123L)),
            received,
        )
    }

    @Test
    fun honorEmptyTreeConversationRowClickSchedulesScreenshotFallback() {
        assertTrue(shouldCaptureEmptyTreeWeChatOpen(
            eventType = AccessibilityEvent.TYPE_VIEW_CLICKED,
            className = "android.widget.LinearLayout",
            visibleText = "17:20",
            activeTreeUsable = false,
            sourceTreeUsable = false,
        ))
        assertTrue(shouldCaptureEmptyTreeWeChatOpen(
            eventType = AccessibilityEvent.TYPE_VIEW_CLICKED,
            className = null,
            visibleText = "转文字",
            activeTreeUsable = false,
            sourceTreeUsable = false,
        ))
        assertTrue(shouldCaptureEmptyTreeWeChatOpen(
            eventType = AccessibilityEvent.TYPE_VIEW_CLICKED,
            className = "android.widget.Button",
            visibleText = "发送",
            activeTreeUsable = false,
            sourceTreeUsable = false,
        ))
        assertFalse(shouldCaptureEmptyTreeWeChatOpen(
            eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            className = "android.widget.LinearLayout",
            visibleText = "17:20",
            activeTreeUsable = false,
            sourceTreeUsable = false,
        ))
        assertFalse(shouldCaptureEmptyTreeWeChatOpen(
            eventType = AccessibilityEvent.TYPE_VIEW_CLICKED,
            className = "android.widget.LinearLayout",
            visibleText = "17:20",
            activeTreeUsable = true,
            sourceTreeUsable = false,
        ))
    }

    @Test
    fun weChatVoiceTranscriptClickUsesBoundedDelayedScreenshots() {
        assertTrue(shouldCaptureEmptyTreeWeChatOpen(
            eventType = AccessibilityEvent.TYPE_VIEW_CLICKED,
            className = "android.widget.TextView",
            visibleText = "转文字",
            activeTreeUsable = false,
            sourceTreeUsable = false,
        ))
        assertEquals(listOf(1_500L, 6_000L), emptyTreeWeChatCaptureDelays("转文字"))
        assertEquals(listOf(0L, 350L, 900L, 2_200L, 4_400L), emptyTreeWeChatCaptureDelays("发送"))
        assertTrue(emptyTreeWeChatCaptureDelays("播放语音").isEmpty())
    }
    @Test fun entryProbesOutliveTwoCandidateCooldownsWithoutPollingForever() {
        for (delays in listOf(
            foregroundChatProbeDelays(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "android.widget.FrameLayout"),
            emptyTreeWeChatCaptureDelays("工作群 昨天 你好"),
        )) {
            var now = 0L
            val candidates = EmptyTreeCandidateProbe { now }
            val scope = ScreenshotScope(1, 1)
            val attempts = delays.filter { at -> now = at; candidates.consume(scope) }
            assertEquals(listOf(0L, 2_200L, 4_400L), attempts)
            assertTrue(delays.size <= 5)
            assertTrue(delays.last() <= 5_000L)
        }
    }
    @Test fun listRowCombinedTextAndShortStayHaveImmediateBoundedProbes() {
        assertTrue(shouldCaptureEmptyTreeWeChatOpen(AccessibilityEvent.TYPE_VIEW_CLICKED, "android.widget.LinearLayout", "工作群\n昨天\n你好", false, false))
        assertEquals(listOf(0L, 350L, 900L, 2_200L, 4_400L), emptyTreeWeChatCaptureDelays("工作群\n昨天\n你好"))
        assertEquals(listOf(0L, 350L, 900L, 2_200L, 4_400L), foregroundChatProbeDelays(AccessibilityEvent.TYPE_VIEW_CLICKED, "android.widget.TextView"))
        assertTrue(foregroundChatProbeDelays(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED, "android.widget.EditText").isEmpty())
        assertFalse(shouldCaptureEmptyTreeWeChatOpen(AccessibilityEvent.TYPE_VIEW_CLICKED, "android.widget.LinearLayout", "设置", false, false))
    }
}
