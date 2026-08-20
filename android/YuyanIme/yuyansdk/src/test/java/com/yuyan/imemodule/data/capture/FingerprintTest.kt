package com.yuyan.imemodule.data.capture

import com.yuyan.imemodule.data.capture.model.CapturedMessage
import com.yuyan.imemodule.data.capture.model.ChatDirection
import com.yuyan.imemodule.data.capture.model.ChatMessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FingerprintTest {
    private val sample = CapturedMessage(
        conversationKey = "wechat|account|peer",
        senderKey = "peer",
        direction = ChatDirection.INCOMING,
        messageType = ChatMessageType.TEXT,
        text = "  你好   世界  ",
        displayedTime = "18:30",
        previousContentFingerprint = "1".repeat(64),
        nextContentFingerprint = "2".repeat(64),
        sameContentOrdinal = 0,
        viewportIndex = 1,
    )

    @Test
    fun normalizesOuterAndRepeatedWhitespace() {
        assertEquals("你好 世界", normalizeCapturedText("  你好 \n\t  世界  "))
        assertEquals(
            contentFingerprint(sample),
            contentFingerprint(sample.copy(text = "你好 世界")),
        )
    }

    @Test
    fun sameMessageInOverlappingViewportHasSameFingerprint() {
        val first = messageFingerprint(sample.copy(viewportIndex = 1))
        val second = messageFingerprint(sample.copy(viewportIndex = 5))
        assertEquals(first, second)
    }

    @Test
    fun sameTextAtDifferentDisplayedTimeHasDifferentFingerprint() {
        val first = messageFingerprint(sample.copy(displayedTime = "18:30"))
        val second = messageFingerprint(sample.copy(displayedTime = "18:31"))
        assertNotEquals(first, second)
    }

    @Test
    fun sameAssetBytesHaveSameSha256() {
        val bytes = "same asset".toByteArray()
        assertEquals(sha256(bytes), sha256(bytes.copyOf()))
        assertEquals(64, sha256(bytes).length)
    }

    @Test
    fun missingConversationKeyReturnsNullInsteadOfGuessing() {
        assertNull(messageFingerprint(sample.copy(conversationKey = null)))
        assertNull(messageFingerprint(sample.copy(conversationKey = "   ")))
    }
}
