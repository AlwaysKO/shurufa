package com.yuyan.imemodule.data.redpacket

import android.graphics.Bitmap
import android.graphics.Color
import com.yuyan.imemodule.data.capture.ui.IntRect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SilentPacketReaderTest {
    @Test fun prepareRecognizesOnlySmallBlankLocalBitmapAndReleasesIt() = runBlocking {
        var input: Bitmap? = null
        val reader = SilentPacketReader(recognize = { bitmap ->
            input = bitmap
            assertEquals(64, bitmap.width)
            assertEquals(64, bitmap.height)
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width)
                assertEquals(Color.WHITE, bitmap.getPixel(x, y))
            emptyList()
        })
        assertTrue(reader.prepare { true })
        assertTrue(input!!.isRecycled)
    }
    @Test fun prepareDoesNotRecognizeWithoutPermissionOrAfterProtection() = runBlocking {
        var calls = 0
        val reader = SilentPacketReader(recognize = { calls++; emptyList() })
        assertFalse(reader.prepare { false })
        assertEquals(0, calls)
    }
    @Test fun prepareRejectsProtectionActivatedDuringRecognitionAndReleasesInput() = runBlocking {
        var input: Bitmap? = null
        var allowed = true
        val reader = SilentPacketReader(recognize = { input = it; allowed = false; emptyList() })
        assertFalse(reader.prepare { allowed })
        assertTrue(input!!.isRecycled)
    }
    @Test fun prepareFailureReleasesInput() = runBlocking {
        var input: Bitmap? = null
        val reader = SilentPacketReader(recognize = { input = it; throw IllegalStateException("test") })
        try {
            reader.prepare { true }
            fail("recognition failure must propagate")
        } catch (_: IllegalStateException) { assertTrue(input!!.isRecycled) }
    }
    @Test fun prepareCancellationWaitsForTaskThenReleasesInputAndPropagates() = runBlocking {
        var input: Bitmap? = null
        val started = CompletableDeferred<Unit>()
        val completion = CompletableDeferred<Unit>()
        var cancelled = false
        val reader = SilentPacketReader(recognize = {
            input = it; started.complete(Unit); completion.await(); emptyList()
        })
        val job = launch {
            try { reader.prepare { true } }
            catch (error: CancellationException) { cancelled = true; throw error }
        }
        yield()
        assertNotNull(input)
        started.await()
        job.cancel()
        assertFalse(input!!.isRecycled)
        completion.complete(Unit)
        job.join()
        assertTrue(cancelled)
        assertTrue(input!!.isRecycled)
    }

    @Test fun parsesInMemoryBitmapAndReleasesOwnedCapture() = runBlocking {
        val bitmap = Bitmap.createBitmap(1000, 2200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        for (y in 650 until 870) for (x in 150 until 760) bitmap.setPixel(x, y, Color.rgb(255, 155, 45))
        listOf(909, 925, 941).forEach { left ->
            for (y in 122 until 128) for (x in left until left + 6) bitmap.setPixel(x, y, Color.BLACK)
        }
        val reader = SilentPacketReader(recognize = { listOf(
            PacketVisualLine("测试群(12)", IntRect(310, 100, 690, 160)),
            PacketVisualLine("微信红包", IntRect(180, 820, 350, 850))) })
        val snapshot = reader.read(SilentPacketFrame(bitmap, 1, 1000, 7), { true })!!
        assertEquals("测试群", snapshot.match.page.chatName)
        assertEquals(1, snapshot.match.page.cards.size)
        assertEquals(7, snapshot.windowId)
        assertTrue(snapshot.signatures.values.all { it.isNotBlank() })
        assertTrue(bitmap.isRecycled)
    }
    @Test fun rejectedCaptureIsReleasedWithoutRecognition() = runBlocking {
        for (primary in listOf(true, false)) {
            val bitmap = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)
            var calls = 0
            val reader = SilentPacketReader(recognize = { calls++; emptyList() })
            assertNull(reader.read(SilentPacketFrame(bitmap, 1, 1000, if (primary) 0 else 7), { primary }))
            assertEquals(0, calls); assertTrue(bitmap.isRecycled)
        }
    }
    @Test fun cancelledRecognitionRetainsBitmapUntilTaskCompletionThenReleasesIt() = runBlocking {
        val bitmap = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)
        val started = CompletableDeferred<Unit>()
        val completion = CompletableDeferred<Unit>()
        val reader = SilentPacketReader(recognize = { started.complete(Unit); completion.await(); emptyList() })
        val job = launch { reader.read(SilentPacketFrame(bitmap, 1, 1000, 7), { true }) }
        started.await()
        job.cancel()
        assertFalse(bitmap.isRecycled)
        completion.complete(Unit)
        job.join()
        assertTrue(bitmap.isRecycled)
    }
    @Test fun protectionActivatedDuringRecognitionDiscardsResultAndReleasesImage() = runBlocking {
        val bitmap = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)
        var allowed = true
        val reader = SilentPacketReader(recognize = { allowed = false; emptyList() })
        assertNull(reader.read(SilentPacketFrame(bitmap, 1, 1000, 7), { allowed }))
        assertTrue(bitmap.isRecycled)
    }
}
