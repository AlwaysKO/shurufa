package com.yuyan.imemodule.data.capture.page

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class PageCaptureOutboxTest {
    private lateinit var app: Application
    private fun frame(data: String = "image", kind: PageKind? = PageKind.PAYMENT) =
        PageFrame(PageDecision(kind, "verified"), data.toByteArray(), 500, 1000)
    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        app.deleteDatabase(File(app.noBackupFilesDir, "page_capture_outbox.db").absolutePath)
    }
    @Test fun committedImageAndMetadataSurviveReopen() {
        val id = PageCaptureOutbox(app).use { s ->
            val saved = s.enqueue("com.tencent.mm", frame(), 1000)
            assertEquals(PageWriteStatus.SAVED, saved.status); saved.id!!
        }
        PageCaptureOutbox(app).use { s ->
            val row = s.pending().single()
            assertEquals(id, row.id); assertEquals("com.tencent.mm", row.packageName)
            assertEquals(PageKind.PAYMENT, row.kind); assertEquals(1000L, row.capturedAt)
            assertEquals(500, row.width); assertEquals(1000, row.height)
            assertEquals(64, row.sha256.length)
            assertArrayEquals("image".toByteArray(), s.image(id))
        }
    }
    @Test fun duplicatesReusePendingImageButDoNotMergeDifferentAppsOrKinds() {
        PageCaptureOutbox(app).use { s ->
            val first = s.enqueue("com.tencent.mm", frame(), 1000)
            val second = s.enqueue("com.tencent.mm", frame(), 2000)
            assertEquals(PageWriteStatus.DUPLICATE, second.status); assertEquals(first.id, second.id)
            assertEquals(PageWriteStatus.SAVED, s.enqueue("com.ss.android.ugc.aweme", frame(), 2000).status)
            assertEquals(PageWriteStatus.SAVED, s.enqueue("com.tencent.mm", frame(kind = PageKind.CONVERSATION_LIST), 2000).status)
            assertEquals(3, s.pending().size)
        }
    }
    @Test fun byteBudgetPreservesOldUnacknowledgedImage() {
        PageCaptureOutbox(app, maxBytes = 8).use { s ->
            val old = s.enqueue("com.tencent.mm", frame("12345"), 1000)
            assertEquals(PageWriteStatus.FULL, s.enqueue("com.tencent.mm", frame("67890"), 2000).status)
            assertEquals(PageWriteStatus.DUPLICATE, s.enqueue("com.tencent.mm", frame("12345"), 3000).status)
            assertEquals(1, s.pending().size); assertArrayEquals("12345".toByteArray(), s.image(old.id!!))
        }
    }
    @Test fun recordBudgetDoesNotEvictOrClearPendingPictures() {
        PageCaptureOutbox(app, maxRecords = 1).use { s ->
            assertEquals(PageWriteStatus.SAVED, s.enqueue("com.tencent.mm", frame(), 1000).status)
            assertEquals(PageWriteStatus.FULL, s.enqueue("com.tencent.mm", frame("another"), 2000).status)
            assertEquals(1, s.pending().size)
        }
    }
    @Test fun failedInsertDoesNotLeaveOrphanMetadataOrImage() {
        PageCaptureOutbox(app).use { s ->
            s.writableDatabase.execSQL("CREATE TRIGGER fail_page BEFORE INSERT ON pages BEGIN SELECT RAISE(ABORT, 'disk failure'); END")
            assertThrows(Exception::class.java) { s.enqueue("com.tencent.mm", frame(), 1000) }
            assertTrue(s.pending().isEmpty())
            s.writableDatabase.execSQL("DROP TRIGGER fail_page")
            assertEquals(PageWriteStatus.SAVED, s.enqueue("com.tencent.mm", frame(), 1000).status)
        }
    }
    @Test fun unknownChatUnsupportedOrInvalidFramesCannotEnterPageQueue() {
        PageCaptureOutbox(app).use { s ->
            for ((pkg, image, at) in listOf(
                Triple("com.tencent.mm", frame(kind = PageKind.CHAT), 1000L),
                Triple("com.tencent.mm", frame(kind = null), 1000L),
                Triple("com.taobao.taobao", frame(), 1000L),
                Triple("com.tencent.mm", frame(""), 1000L),
                Triple("com.tencent.mm", frame().copy(width = 0), 1000L),
                Triple("com.tencent.mm", frame(), -1L),
            )) assertEquals(PageWriteStatus.INVALID, s.enqueue(pkg, image, at).status)
            assertTrue(s.pending().isEmpty())
        }
    }
    @Test fun revocationDuringTransactionRollsBackNewFrame() {
        PageCaptureOutbox(app).use { s ->
            var checks = 0
            val result = s.enqueue("com.tencent.mm", frame(), 1000) { ++checks == 1 }
            assertEquals(PageWriteStatus.DISCARDED, result.status)
            assertTrue(s.pending().isEmpty())
        }
    }
    @Test fun acceptedFrameHandoffSurvivesOrdinaryJobCancellation() = kotlinx.coroutines.runBlocking {
        PageCaptureOutbox(app).use { s ->
            val job = launch {
                kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!.cancel()
                persistAcceptedPage(s, "com.tencent.mm", frame(), 1000) { true }
            }
            job.join()
            assertEquals(1, s.pending().size)
        }
    }
    @Test fun acceptedFrameHandoffCannotBypassRevokedConsent() = kotlinx.coroutines.runBlocking {
        PageCaptureOutbox(app).use { s ->
            val result = persistAcceptedPage(s, "com.tencent.mm", frame(), 1000) { false }
            assertEquals(PageWriteStatus.DISCARDED, result.status)
            assertTrue(s.pending().isEmpty())
        }
    }
    @Test fun acceptedProbeFrameIsSavedBeforeCancelledDispatcherReturn() = kotlinx.coroutines.runBlocking {
        PageCaptureOutbox(app).use { s ->
            val job = kotlinx.coroutines.Job()
            val bitmap = android.graphics.Bitmap.createBitmap(500, 1000, android.graphics.Bitmap.Config.ARGB_8888)
            val bounds = com.yuyan.imemodule.data.capture.ui.IntRect(0, 0, 500, 1000)
            val labels = listOf("微信", "通讯录", "发现", "我").mapIndexed { i, name ->
                PageLabel(name, com.yuyan.imemodule.data.capture.ui.IntRect(i * 110, 950, i * 110 + 80, 980))
            } + PageLabel("微信", com.yuyan.imemodule.data.capture.ui.IntRect(10, 50, 90, 80))
            var checks = 0
            val probe = PageFrameProbe(
                com.yuyan.imemodule.data.capture.media.ScreenshotSource { _, _ ->
                    com.yuyan.imemodule.data.capture.media.WindowScreenshotResult.Success(bitmap, 0, 0)
                }, recognize = { labels }, allowed = { if (++checks == 4) job.cancel(); true },
            )
            val task = launch(job) {
                captureAndPersistAcceptedPage(s, "com.tencent.mm", 1000, authorized = { true }) { accepted ->
                    probe.capture("com.tencent.mm", 1, bounds, emptyList(), current = { true }, onAccepted = accepted)
                }
            }
            task.join()
            assertEquals(4, checks)
            assertEquals(1, s.pending().size)
            assertTrue(bitmap.isRecycled)
        }
    }
    @Test fun largeImageCanBeReadBackWithoutDependingOnOneGiantCursorRow() {
        PageCaptureOutbox(app).use { s ->
            val bytes = ByteArray(3 * 1024 * 1024) { (it % 251).toByte() }
            val saved = s.enqueue("com.tencent.mm", frame().copy(bytes = bytes), 1000)
            assertEquals(PageWriteStatus.SAVED, saved.status)
            assertArrayEquals(bytes, s.image(saved.id!!))
        }
    }
    @Test fun persistenceRunsAfterCaptureCleanupNotInsideItsPhysicalSlot() = kotlinx.coroutines.runBlocking {
        PageCaptureOutbox(app).use { s ->
            var released = false
            val result = captureAndPersistAcceptedPage(s, "com.tencent.mm", 1000,
                authorized = { assertTrue(released); true }) { accepted ->
                try { accepted(frame()) } finally { released = true }
            }
            assertEquals(PageWriteStatus.SAVED, result.status)
        }
    }
    @Test fun corruptedImageHashCannotBeReadAsValidUploadPayload() {
        PageCaptureOutbox(app).use { s ->
            val id = s.enqueue("com.tencent.mm", frame(), 1000).id!!
            s.writableDatabase.execSQL("UPDATE pages SET image=? WHERE id=?", arrayOf("wrong".toByteArray(), id))
            assertThrows(IllegalStateException::class.java) { s.image(id) }
            assertEquals(1, s.pending().size)
        }
    }
}
