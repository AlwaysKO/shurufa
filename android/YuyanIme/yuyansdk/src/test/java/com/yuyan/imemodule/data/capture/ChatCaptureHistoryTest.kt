package com.yuyan.imemodule.data.capture

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.data.capture.adapter.chatDiagnosticHistoryEvent
import com.yuyan.imemodule.data.collect.CaptureHistoryEntry
import com.yuyan.imemodule.data.collect.CaptureHistoryStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ChatCaptureHistoryTest {
    @Test fun successiveFailuresAndSuccessSurviveLatestStatusReplacementAndReopen() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val stages = listOf(
            chatDiagnosticHistoryEvent("wechat", "screenshot", "cancelled"),
            chatDiagnosticHistoryEvent("wechat", "screenshot", "ready"),
            chatDiagnosticHistoryEvent("wechat", "persist", "inserted"),
        ).map { requireNotNull(it) }
        val now = System.currentTimeMillis()
        CaptureHistoryStore(app).use { store ->
            store.writableDatabase.delete("events", null, null)
            store.append(stages.mapIndexed { index, event -> CaptureHistoryEntry(now + index, event) }, now)
        }
        CaptureHistoryStore(app).use { store ->
            assertEquals(listOf("CHAT_SCREENSHOT_CANCELLED", "CHAT_FRAME_READY", "CHAT_PERSIST_INSERTED"),
                store.recent(now).map { it.event.name })
        }
    }

    @Test fun historyAcceptsOnlyKnownWechatStagesAndStatuses() {
        assertNull(chatDiagnosticHistoryEvent("douyin", "screenshot", "ready"))
        assertNull(chatDiagnosticHistoryEvent("wechat", "screenshot", "private-body-sentinel"))
        assertNull(chatDiagnosticHistoryEvent("wechat", "private-title-sentinel", "ready"))
        assertNull(chatDiagnosticHistoryEvent("wechat", "page", "empty_tree"))
        assertEquals("CHAT_PERSIST_DUPLICATE", chatDiagnosticHistoryEvent("wechat", "persist", "duplicate")?.name)
        assertEquals("CHAT_PERSIST_FAILED", chatDiagnosticHistoryEvent("wechat", "persist", "failed")?.name)
    }

    @Test fun deliveryHistoryKeepsAcknowledgementAndFailureButNotRepeatedWaiting() {
        assertEquals("CHAT_UPLOAD_ACKNOWLEDGED", chatDiagnosticHistoryEvent("wechat", "upload", "acknowledged")?.name)
        assertEquals("CHAT_UPLOAD_FAILED", chatDiagnosticHistoryEvent("wechat", "upload", "failed")?.name)
        assertNull(chatDiagnosticHistoryEvent("wechat", "upload", "waiting"))
        assertNull(chatDiagnosticHistoryEvent("wechat", "upload", "private-payload-sentinel"))
        assertNull(chatDiagnosticHistoryEvent("douyin", "upload", "acknowledged"))
    }

    @Test fun cancelledSendGetsATerminalEventWithoutPersistingTreeSupersessionFlood() {
        assertEquals("CHAT_PROMPT_CANCELLED", captureTraceHistoryEvent(CaptureStage.PROMPT_CANCELLED)?.name)
        assertNull(captureTraceHistoryEvent(CaptureStage.SUPERSEDED))
        assertNull(captureTraceHistoryEvent(CaptureStage.REQUEST_CANCELLED))
    }

    @Test fun promptHistoryExplainsRenderAndPermissionFailuresWithoutSavingEventFlood() {
        fun event(stage: CaptureStage, value: Int = -1, flag: Boolean = false) =
            captureTraceHistoryEvent(stage, value, flag)?.name
        assertEquals("CHAT_SEND_REQUESTED", event(CaptureStage.SEND_REQUESTED))
        assertEquals("CHAT_PROMPT_STARTED", event(CaptureStage.PROMPT_STEP, 0, true))
        assertEquals("CHAT_PROMPT_RENDER_UNSETTLED", event(CaptureStage.PROMPT_STEP, 1, false))
        assertEquals("CHAT_PROMPT_RENDER_READY", event(CaptureStage.PROMPT_STEP, 1, true))
        assertEquals("CHAT_PROMPT_FRAME_BLOCKED", event(CaptureStage.PROMPT_STEP, 2, false))
        assertEquals("CHAT_PROMPT_FRAME_ALLOWED", event(CaptureStage.PROMPT_STEP, 2, true))
        assertEquals("CHAT_IDENTITY_REJECTED", event(CaptureStage.IDENTITY_REJECTED))
        assertEquals("CHAT_CONTENT_DUPLICATE", event(CaptureStage.CONTENT_DUPLICATE))
        assertNull(event(CaptureStage.EVENT))
        assertNull(event(CaptureStage.TREE))
        assertNull(event(CaptureStage.SEND_RENDER))
        assertNull(event(CaptureStage.PROMPT_STEP, 3))
    }
}
