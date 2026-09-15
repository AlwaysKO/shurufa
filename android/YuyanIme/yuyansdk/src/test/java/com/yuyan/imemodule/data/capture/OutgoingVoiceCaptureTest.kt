package com.yuyan.imemodule.data.capture

import com.yuyan.imemodule.data.capture.model.ChatDirection
import com.yuyan.imemodule.data.capture.model.ChatMessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OutgoingVoiceCaptureTest {
    @Test
    fun wechatVoiceTranscriptBecomesOutgoingTextWithoutClaimingItWasSent() {
        val capture = buildOutgoingVoiceCapture("com.tencent.mm", "  今晚八点见  ", 1_700_000_000_000L)

        requireNotNull(capture)
        assertEquals(ChatDirection.OUTGOING, capture.message.direction)
        assertEquals(ChatMessageType.TEXT, capture.message.messageType)
        assertEquals("今晚八点见", capture.message.text)
        assertEquals("voice", capture.message.metadata["input_mode"])
        assertEquals("transcribed_not_send_confirmed", capture.message.metadata["delivery_status"])
    }

    @Test
    fun nonWechatAndBlankTranscriptsAreSkipped() {
        assertNull(buildOutgoingVoiceCapture("com.example.app", "你好", 1L))
        assertNull(buildOutgoingVoiceCapture("com.tencent.mm", "  ", 1L))
    }
}
