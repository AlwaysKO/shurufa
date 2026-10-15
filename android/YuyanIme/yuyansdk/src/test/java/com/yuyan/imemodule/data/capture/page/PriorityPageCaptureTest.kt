package com.yuyan.imemodule.data.capture.page

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class PriorityPageCaptureTest {
    @Test fun earlyUnknownProbeKeepsPaymentTimingWithoutSpendingVideoBudget() {
        assertEquals(setOf(PageKind.PAYMENT), reservePriorityPageKinds(
            PageDecision(null, "insufficient_evidence"), { true },
            { error("video must wait three seconds") }, feedReady = false))
        assertTrue(reservePriorityPageKinds(PageDecision(PageKind.MEDIA_FEED, "feed_navigation"),
            { error("feed cannot use payment budget") }, { error("too early") }, feedReady = false).isEmpty())
    }
    @Test fun ordinaryQuotaCannotPersistPaymentOrFeedEvenWhenTreeWasUnknown() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.deleteDatabase(File(app.noBackupFilesDir, "page_capture_outbox.db").absolutePath)
        PageCaptureOutbox(app).use { outbox ->
            for (kind in listOf(PageKind.PAYMENT, PageKind.MEDIA_FEED, PageKind.VIDEO_CALL)) {
                val result = captureAndPersistAcceptedPage(outbox, "com.tencent.mm", 1000,
                    authorized = { true }, acceptKind = ::acceptOrdinaryPageKind) { accepted ->
                    accepted(PageFrame(PageDecision(kind, "same_frame_verified"), "image".toByteArray(), 500, 1000))
                }
                assertEquals(PageWriteStatus.NO_FRAME, result.status)
            }
            assertTrue(outbox.pending().isEmpty())
            assertTrue(acceptOrdinaryPageKind(PageKind.MINI_APP))
            assertTrue(acceptOrdinaryPageKind(PageKind.IMAGE_POST))
        }
    }

    @Test fun knownPaymentDoesNotSpendOrDependOnVideoQuota() {
        val kinds = reservePriorityPageKinds(PageDecision(PageKind.PAYMENT, "receipt_structure"),
            { true }, { error("payment must not query video quota") })
        assertEquals(setOf(PageKind.PAYMENT), kinds)
    }
    @Test fun unknownPageCanSavePaymentWhenVideoQuotaIsExhausted() {
        assertEquals(setOf(PageKind.PAYMENT), reservePriorityPageKinds(
            PageDecision(null, "insufficient_evidence"), { true }, { false }))
        assertEquals(setOf(PageKind.MEDIA_FEED), reservePriorityPageKinds(
            PageDecision(null, "insufficient_evidence"), { false }, { true }))
        assertTrue(reservePriorityPageKinds(PageDecision(null, "insufficient_evidence"), { false }, { false }).isEmpty())
    }
    @Test fun knownFeedDoesNotConsumePaymentBudgetAndOtherPagesNeverUsePriorityQuota() {
        assertEquals(setOf(PageKind.MEDIA_FEED), reservePriorityPageKinds(PageDecision(PageKind.MEDIA_FEED, "feed_navigation"),
            { error("feed must not query payment quota") }, { true }))
        for (decision in listOf(PageDecision(PageKind.CHAT, "verified_chat_adapter"),
            PageDecision(PageKind.MINI_APP, "verified_mini_app_chrome"), PageDecision(PageKind.IMAGE_POST, "feed_navigation"),
            PageDecision(null, "sensitive_input"), PageDecision(null, "editable_non_chat"),
            PageDecision(null, "secure_window"), PageDecision(null, "invalid_bounds"))) {
            assertTrue(reservePriorityPageKinds(decision, { error("unexpected payment reserve") }, { error("unexpected video reserve") }).isEmpty())
        }
    }
    @Test fun onlyReservedKindIsPersistedAndDuplicatePaymentReusesPendingAndReceipt() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.deleteDatabase(File(app.noBackupFilesDir, "page_capture_outbox.db").absolutePath)
        val acceptedKinds = reservePriorityPageKinds(PageDecision(null, "insufficient_evidence"), { true }, { false })
        PageCaptureOutbox(app).use { outbox ->
            suspend fun save(kind: PageKind, bytes: String = "payment-image") = captureAndPersistAcceptedPage(
                outbox, "com.tencent.mm", 1000, authorized = { true }, acceptKind = { it in acceptedKinds }
            ) { accepted -> accepted(PageFrame(PageDecision(kind, "same_frame_verified"), bytes.toByteArray(), 500, 1000)) }
            for (kind in listOf(PageKind.MEDIA_FEED, PageKind.MINI_APP, PageKind.CHAT, PageKind.VIDEO_CALL)) {
                assertEquals(PageWriteStatus.NO_FRAME, save(kind).status)
                assertTrue(outbox.pending().isEmpty())
            }
            val first = save(PageKind.PAYMENT)
            assertEquals(PageWriteStatus.SAVED, first.status)
            val duplicate = save(PageKind.PAYMENT)
            assertEquals(PageWriteStatus.DUPLICATE, duplicate.status)
            assertEquals(first.id, duplicate.id)
            val row = outbox.pending().single()
            assertTrue(outbox.acknowledge(row) { true })
            assertEquals(PageWriteStatus.DUPLICATE, save(PageKind.PAYMENT).status)
            assertTrue(outbox.pending().isEmpty())
            assertEquals(PageWriteStatus.SAVED, save(PageKind.PAYMENT, "changed-payment-image").status)
        }
    }
}
