package com.yuyan.imemodule.data.capture

import android.graphics.Bitmap
import android.graphics.Color
import com.yuyan.imemodule.data.capture.media.ScreenshotContentInput
import com.yuyan.imemodule.data.capture.media.ScreenshotConversationIdentity
import com.yuyan.imemodule.data.capture.model.ConversationType
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConfirmedScreenshotContentTest {
    private val identity = ScreenshotConversationIdentity(
        externalKey = "screenshot-v2:" + "a".repeat(64), displayName = "测试会话",
        conversationType = ConversationType.DIRECT, confidence = .95, source = "test",
        status = "confirmed", exactTitleHash = "exact-title",
    )

    @Test fun onlySameFrameConfirmedIdentityCanExposeVerifiedBodyEvidence() {
        val bitmap = Bitmap.createBitmap(40, 80, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.rgb(238, 238, 238))
            bitmap.setPixel(10, 20, Color.BLACK)
            bitmap.setPixel(10, 40, Color.BLACK)
            val input = ScreenshotContentInput(0, detectBlocks = true)
            input.captureFrom(bitmap)
            var reason: ScreenshotContentReason? = null
            assertEquals(1, identity.confirmedContent(input) { reason = it }.size)
            assertEquals(ScreenshotContentReason.READY, reason)
            for (untrusted in listOf(identity.copy(status = "pending"), identity.copy(confidence = .79),
                identity.copy(exactTitleHash = null), identity.copy(isChatPage = false))) {
                assertTrue(untrusted.confirmedContent(input) { reason = it }.isEmpty())
                assertEquals(ScreenshotContentReason.IDENTITY_UNVERIFIED, reason)
            }
            assertTrue(identity.copy(previousKey = "old-key").confirmedContent(input) { reason = it }.isEmpty())
            assertEquals(ScreenshotContentReason.CONFIRMATION_REPLAY, reason)
            input.captureFrom(bitmap, bodyBoundaryVerified = false)
            assertTrue(identity.confirmedContent(input) { reason = it }.isEmpty())
            assertEquals(ScreenshotContentReason.BODY_BOUNDARY_UNVERIFIED, reason)
        } finally { bitmap.recycle() }
    }
}
