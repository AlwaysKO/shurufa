package com.yuyan.imemodule.data.redpacket

import android.graphics.Bitmap
import com.yuyan.imemodule.data.capture.ui.IntRect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SilentPacketReadDiagnosticTest {
    private fun frame(display: Int = 7, captured: Long = 1000) =
        SilentPacketFrame(Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888), 1, captured, display)
    private fun snapshot(frame: SilentPacketFrame) = PacketVisualSnapshot(
        PacketVisualMatch(PacketPage(), emptyMap()), frame.displayId, IntRect(0, 0, 100, 200), 0, 0, frame.capturedAt)

    @Test fun validReadReturnsOnlyTimingAndReleasesCapture() = runBlocking {
        val frame = frame()
        var now = 1100L
        val diagnostic = inspectSilentPacketFrame(frame, { true }, { now = 1400; snapshot(it) }, { now })
        assertEquals(SilentPacketReadResult.VALID, diagnostic.result)
        assertEquals(300, diagnostic.elapsedMs)
        assertEquals(400, diagnostic.frameAgeMs)
        assertTrue(frame.bitmap.isRecycled)
    }
    @Test fun coldOcrOverOneSecondRemainsStaleWithoutRelaxingTtl() = runBlocking {
        val frame = frame()
        var now = 1000L
        val diagnostic = inspectSilentPacketFrame(frame, { true }, { now = 2600; snapshot(it) }, { now })
        assertEquals(SilentPacketReadResult.FRAME_STALE, diagnostic.result)
        assertEquals(1600, diagnostic.elapsedMs)
        assertEquals(1600, diagnostic.frameAgeMs)
        assertTrue(frame.bitmap.isRecycled)
    }
    @Test fun invalidOrAlreadyStaleFrameNeverCallsReader() = runBlocking {
        for ((frame, expected) in listOf(frame(0) to SilentPacketReadResult.FRAME_INVALID,
            frame(captured = 0) to SilentPacketReadResult.FRAME_STALE,
            frame(captured = 2000) to SilentPacketReadResult.FRAME_STALE)) {
            val diagnostic = inspectSilentPacketFrame(frame, { true }, { fail("reader must not run"); null }, { 1100 })
            assertEquals(expected, diagnostic.result)
            assertTrue(frame.bitmap.isRecycled)
        }
    }
    @Test fun nullOrInconsistentSnapshotIsRejected() = runBlocking {
        for (mismatch in listOf(false, true)) {
            val frame = frame()
            val diagnostic = inspectSilentPacketFrame(frame, { true }, {
                if (mismatch) snapshot(it).copy(windowId = 9) else null
            }, { 1100 })
            assertEquals(SilentPacketReadResult.READ_REJECTED, diagnostic.result)
            assertTrue(frame.bitmap.isRecycled)
        }
    }
    @Test fun readerFailureHasNoExceptionMessageAndReleasesCapture() = runBlocking {
        val frame = frame()
        val diagnostic = inspectSilentPacketFrame(frame, { true }, { throw IllegalStateException("private text") }, { 1100 })
        assertEquals(SilentPacketReadResult.READ_FAILED, diagnostic.result)
        assertFalse(diagnostic.toString().contains("private text"))
        assertTrue(frame.bitmap.isRecycled)
    }
    @Test fun cancellationPropagatesAndReleasesCapture() = runBlocking {
        val frame = frame()
        try {
            inspectSilentPacketFrame(frame, { true }, { throw CancellationException("cancel") }, { 1100 })
            fail("cancellation must propagate")
        } catch (_: CancellationException) { assertTrue(frame.bitmap.isRecycled) }
    }
    @Test fun protectionBeforeAndDuringReadRejectsFrame() = runBlocking {
        for (initial in listOf(false, true)) {
            val frame = frame()
            var allowed = initial
            val diagnostic = inspectSilentPacketFrame(frame, { allowed }, {
                if (!initial) fail("reader must not run")
                allowed = false
                snapshot(it)
            }, { 1100 })
            assertEquals(SilentPacketReadResult.PROTECTED, diagnostic.result)
            assertTrue(frame.bitmap.isRecycled)
        }
    }
}
