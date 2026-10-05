package com.yuyan.imemodule.data.capture.notification

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class DeferredNotificationMediaTest {
    private val root = Files.createTempDirectory("notification-media").toFile()
    private fun snapshot(time: Long = 1000) = NotificationSnapshot("com.tencent.mm", "notice", "群聊", "[图片]",
        time, true, "小明", "content://temporary/image", true, "[图片]", true, time, "peer", "profile-10")
    private fun store(maximumItems: Int = 32, maximumItemBytes: Long = 100, maximumTotalBytes: Long = 200) =
        DeferredNotificationMedia(root, maximumItems, maximumItemBytes, maximumTotalBytes)
    @After fun cleanup() { root.deleteRecursively() }

    @Test fun stagingPreservesOriginalBytesAndAllSnapshotFieldsAcrossRestart() {
        val bytes = byteArrayOf(1, 4, 9, 16)
        assertEquals(DeferredMediaStage.Stored, store().stage(snapshot()) { bytes.inputStream() })
        val restored = store().pending().single()
        assertEquals(snapshot(), restored.snapshot)
        assertArrayEquals(bytes, restored.file.readBytes())
        assertEquals(DeferredMediaStage.AlreadyStored, store().stage(snapshot()) { error("URI expired") })
    }

    @Test fun pausedWorkerDoesNotDecodeOrPersistAndCanResumeFromPrivateCopy() = runBlocking {
        store().stage(snapshot()) { byteArrayOf(1, 2).inputStream() }
        var invoked = false
        assertFalse(store().processNext({ false }) { _, _ -> invoked = true; true })
        assertFalse(invoked)
        assertEquals(1, store().pending().size)
        assertTrue(store().processNext({ true }) { saved, file ->
            assertEquals(snapshot(), saved)
            assertArrayEquals(byteArrayOf(1, 2), file.readBytes())
            true
        })
        assertTrue(store().pending().isEmpty())
    }

    @Test fun failedAcknowledgementAndGameStartingDuringProcessingKeepTheTask() = runBlocking {
        val queue = store()
        queue.stage(snapshot()) { byteArrayOf(7).inputStream() }
        assertFalse(queue.processNext({ true }) { _, _ -> false })
        var allowed = true
        assertFalse(queue.processNext({ allowed }) { _, _ -> allowed = false; true })
        assertEquals(1, store().pending().size)
        try { queue.processNext({ true }) { _, _ -> throw CancellationException("paused") }; fail("propagate cancellation") }
        catch (_: CancellationException) { }
        assertEquals(1, store().pending().size)
    }

    @Test fun fullQueueRejectsNewMediaWithoutDeletingPreviouslyStagedTasks() {
        val queue = store(maximumItems = 1)
        assertEquals(DeferredMediaStage.Stored, queue.stage(snapshot()) { byteArrayOf(1).inputStream() })
        assertTrue(queue.stage(snapshot(2000)) { error("must reject before reading") } is DeferredMediaStage.Rejected)
        assertEquals(snapshot(), store().pending().single().snapshot)
    }

    @Test fun itemAndTotalByteLimitsNeverCommitTruncatedMedia() {
        val queue = store(maximumItemBytes = 3, maximumTotalBytes = 4)
        assertTrue(queue.stage(snapshot()) { byteArrayOf(1, 2, 3, 4).inputStream() } is DeferredMediaStage.Rejected)
        assertTrue(queue.pending().isEmpty())
        assertEquals(DeferredMediaStage.Stored, queue.stage(snapshot()) { byteArrayOf(1, 2, 3).inputStream() })
        assertTrue(queue.stage(snapshot(2000)) { byteArrayOf(1, 2).inputStream() } is DeferredMediaStage.Rejected)
        assertArrayEquals(byteArrayOf(1, 2, 3), queue.pending().single().file.readBytes())
    }

    @Test fun failedSourceDoesNotCommitAndSameNotificationCanBeRetried() {
        val queue = store()
        assertTrue(queue.stage(snapshot()) { throw IOException("temporary unavailable") } is DeferredMediaStage.Rejected)
        assertTrue(queue.pending().isEmpty())
        assertEquals(DeferredMediaStage.Stored, queue.stage(snapshot()) { byteArrayOf(1).inputStream() })
    }
}
