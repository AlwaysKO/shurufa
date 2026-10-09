package com.yuyan.imemodule.data.capture.page

import com.yuyan.imemodule.data.capture.ui.IntRect
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class BrowsingPageDriverTest {
    private class Fixture(scope: CoroutineScope) {
        var now = 0L; var epoch = 0L; var allowed = true; var room = true; var quota = true
        var reserves = 0; var captures = 0; var reads = 0
        var restoredInterval: Long? = 0L
        val reservedPackages = mutableListOf<String>()
        val reported = mutableListOf<Pair<String, String>>()
        var read: suspend (BrowsePageToken) -> BrowsePageSnapshot? = { p ->
            reads++; BrowsePageSnapshot(p, IntRect(0, 0, 500, 1000), emptyList())
        }
        var idle: suspend (() -> Boolean) -> Boolean = { it() }
        var wait: suspend (Long) -> Unit = { now += it }
        val driver = BrowsingPageDriver(scope, { now }, { epoch }, { allowed },
            { current -> idle(current) }, { ms -> wait(ms) }, { room },
            { p -> read(p) }, { pkg -> reserves++; reservedPackages += pkg; quota },
            { _, current -> if (current()) captures++; PageWriteResult(PageWriteStatus.SAVED) }, { code, pkg -> reported += code to pkg }, persistentIntervalRemaining = { restoredInterval })
    }
    @Test fun oneEmptyWindowReadGetsOneBoundedRetryWithoutAnotherEvent() = runBlocking {
        val f = Fixture(this)
        f.read = { p ->
            f.reads++
            if (f.reads == 1) null else BrowsePageSnapshot(p, IntRect(0, 0, 500, 1000), emptyList())
        }
        f.driver.changed("com.tencent.mm", 1, BrowsePageEvent.WINDOW)!!.join()
        assertEquals(2, f.reads); assertEquals(1600L, f.now)
        assertEquals(1, f.reserves); assertEquals(1, f.captures)
        f.driver.close().join()
    }
    @Test fun persistentlyEmptyWindowStopsAfterTwoReadsAndNeverReserves() = runBlocking {
        val f = Fixture(this)
        f.read = { f.reads++; null }
        f.driver.changed("com.tencent.mm", 1, BrowsePageEvent.WINDOW)!!.join()
        assertEquals(2, f.reads); assertEquals(0, f.reserves); assertEquals(0, f.captures)
        f.driver.close().join()
    }
    @Test fun leavingDuringEmptyWindowRetryCannotCaptureOldPage() = runBlocking {
        val f = Fixture(this)
        val retryStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.read = { f.reads++; null }
        f.wait = { delay -> if (f.reads > 0) { retryStarted.complete(Unit); release.await() }; f.now += delay }
        val job = f.driver.changed("com.tencent.mm", 1, BrowsePageEvent.WINDOW)!!
        yield()
        // Old implementation ends after the first read; assert before waiting on a signal it never emits.
        assertTrue(retryStarted.isCompleted)
        f.driver.invalidate(); release.complete(Unit); job.join()
        assertEquals(1, f.reads); assertEquals(0, f.captures)
        f.driver.close().join()
    }
    @Test fun restoredPersistentIntervalRetainsCandidateUntilOneDelayedAttempt() = runBlocking {
        val f = Fixture(this); f.restoredInterval = 179200
        f.read = { p ->
            f.reads++; assertEquals(180000L, f.now)
            BrowsePageSnapshot(p, IntRect(0, 0, 500, 1000), emptyList())
        }
        f.driver.changed("com.tencent.mm", 1, BrowsePageEvent.WINDOW)!!.join()
        assertEquals(1, f.reads); assertEquals(1, f.reserves); assertEquals(1, f.captures)
        assertTrue(f.reported.contains("budget_interval" to "com.tencent.mm"))
        yield(); assertEquals(1, f.captures)
        f.driver.close().join()
    }
    @Test fun persistentIntervalWaitRejectsNavigationAndConsentChanges() = runBlocking {
        for (revoke in listOf(false, true)) {
            val f = Fixture(this); f.restoredInterval = 179200
            val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            f.wait = { ms -> if (ms > 800) { started.complete(Unit); release.await() }; f.now += ms }
            val job = f.driver.changed("com.tencent.mm", 1, BrowsePageEvent.WINDOW)!!
            started.await()
            if (revoke) f.epoch++ else f.driver.invalidate()
            release.complete(Unit); job.join()
            assertEquals(0, f.reads); assertEquals(0, f.reserves); assertEquals(0, f.captures)
            f.driver.close().join()
        }
    }
    @Test fun invalidPersistentClockCannotFallThroughToCapture() = runBlocking {
        val f = Fixture(this); f.restoredInterval = null
        f.driver.changed("com.tencent.mm", 1, BrowsePageEvent.WINDOW)!!.join()
        assertEquals(0, f.reads); assertEquals(0, f.reserves)
        assertTrue(f.reported.contains("budget_invalid_clock" to "com.tencent.mm"))
        f.driver.close().join()
    }
    @Test fun contentAnimationAndUnsupportedAppsDoNotTakePictures() = runBlocking {
        val f = Fixture(this)
        assertNull(f.driver.changed("com.tencent.mm", 1, BrowsePageEvent.CONTENT))
        assertNull(f.driver.changed("com.taobao.taobao", 1, BrowsePageEvent.WINDOW))
        assertEquals(0, f.captures); assertEquals(0, f.reserves)
        f.driver.close().join()
    }
    @Test fun genuineWindowEventReservesBeforeCaptureAndDoesNotPoll() = runBlocking {
        val f = Fixture(this)
        f.driver.changed("com.tencent.mm", 1, BrowsePageEvent.WINDOW)!!.join()
        assertEquals(800L, f.now); assertEquals(1, f.reserves); assertEquals(1, f.captures)
        yield(); assertEquals(1, f.captures)
        f.driver.close().join()
    }
    @Test fun twoAppsShareIntervalAndReadFreshPageAfterWaiting() = runBlocking {
        val f = Fixture(this)
        f.driver.changed("com.tencent.mm", 1, BrowsePageEvent.WINDOW)!!.join()
        f.driver.changed("com.ss.android.ugc.aweme", 2, BrowsePageEvent.SCROLL)!!.join()
        assertEquals(180800L, f.now); assertEquals(2, f.captures); assertEquals(2, f.reads)
        assertEquals(listOf("com.tencent.mm", "com.ss.android.ugc.aweme"), f.reservedPackages)
        assertEquals(listOf("saved" to "com.tencent.mm", "saved" to "com.ss.android.ugc.aweme"), f.reported)
        f.driver.close().join()
    }
    @Test fun burstsReplacePendingWorkAndLeavingCancelsIt() = runBlocking {
        val f = Fixture(this); val release = CompletableDeferred<Unit>()
        f.wait = { release.await() }
        val old = f.driver.changed("com.tencent.mm", 1, BrowsePageEvent.WINDOW)!!
        yield()
        val last = f.driver.changed("com.tencent.mm", 1, BrowsePageEvent.SCROLL)!!
        yield(); f.driver.invalidate(); release.complete(Unit)
        old.join(); last.join()
        assertEquals(0, f.reads); assertEquals(0, f.captures)
        f.driver.close().join()
    }
    @Test fun consentEpochChangeWhileWaitingCannotResumeOldCapture() = runBlocking {
        val f = Fixture(this); val idle = CompletableDeferred<Unit>()
        f.idle = { current -> idle.await(); current() }
        val task = f.driver.changed("com.tencent.mm", 1, BrowsePageEvent.WINDOW)!!
        yield(); f.epoch++; idle.complete(Unit); task.join()
        assertEquals(0, f.reserves); assertEquals(0, f.captures)
        f.driver.close().join()
    }
    @Test fun fullQueueAndDeniedQuotaNeverCapture() = runBlocking {
        val f = Fixture(this); f.room = false
        f.driver.changed("com.tencent.mm", 1, BrowsePageEvent.WINDOW)!!.join()
        assertEquals(0, f.reserves)
        f.room = true; f.quota = false
        f.driver.changed("com.tencent.mm", 1, BrowsePageEvent.SCROLL)!!.join()
        assertEquals(1, f.reserves); assertEquals(0, f.captures)
        f.driver.close().join()
    }
    @Test fun changedWindowEvidenceCannotConsumeBudget() = runBlocking {
        val f = Fixture(this)
        f.read = { BrowsePageSnapshot(it.copy(windowId = 99), IntRect(0, 0, 500, 1000), emptyList()) }
        f.driver.changed("com.tencent.mm", 1, BrowsePageEvent.WINDOW)!!.join()
        assertEquals(0, f.reserves); assertEquals(0, f.captures)
        f.driver.close().join()
    }
    @Test fun passwordPageIsRejectedBeforeBudgetOrScreenshot() = runBlocking {
        val f = Fixture(this)
        f.read = { BrowsePageSnapshot(it, IntRect(0, 0, 500, 1000),
            listOf(PageLabel("", IntRect(0, 0, 100, 100), password = true))) }
        f.driver.changed("com.tencent.mm", 1, BrowsePageEvent.WINDOW)!!.join()
        assertEquals(0, f.reserves); assertEquals(0, f.captures)
        f.driver.close().join()
    }
    @Test fun databaseFailureCannotFallThroughToCapture() = runBlocking {
        val scope = this; var captured = false; var error = false; var now = 0L
        val driver = BrowsingPageDriver(scope, { now }, { 0 }, { true }, { it() }, { now += it },
            { true }, { BrowsePageSnapshot(it, IntRect(0, 0, 500, 1000), emptyList()) },
            { throw IllegalStateException("disk failure") },
            { _, _ -> captured = true; PageWriteResult(PageWriteStatus.SAVED) }, { _, _ -> error = true })
        driver.changed("com.tencent.mm", 1, BrowsePageEvent.WINDOW)!!.join()
        assertTrue(error); assertFalse(captured)
        driver.close().join()
    }
    @Test fun verifiedChatWithoutEditableFieldNeverSpendsBrowsingBudget() = runBlocking {
        val f = Fixture(this)
        f.read = { BrowsePageSnapshot(it, IntRect(0, 0, 500, 1000), emptyList(), chatVerified = true) }
        f.driver.changed("com.tencent.mm", 1, BrowsePageEvent.WINDOW)!!.join()
        assertEquals(0, f.reserves); assertEquals(0, f.captures)
        f.driver.close().join()
    }
    @Test fun closeWaitsForNonCancellableAcceptedFrameCleanup() = runBlocking {
        var now = 0L; val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var saved = false
        val driver = BrowsingPageDriver(this, { now }, { 0 }, { true }, { it() }, { now += it },
            { true }, { BrowsePageSnapshot(it, IntRect(0, 0, 500, 1000), emptyList()) }, { true },
            { _, _ -> withContext(NonCancellable) { started.complete(Unit); release.await(); saved = true }
                PageWriteResult(PageWriteStatus.SAVED) }, { _, _ -> })
        driver.changed("com.tencent.mm", 1, BrowsePageEvent.WINDOW)
        started.await()
        val drain = driver.close()
        assertFalse(drain.isCompleted)
        release.complete(Unit); drain.join()
        assertTrue(saved)
    }
    @Test fun lateWindowHintCannotReplaceNewerNavigation() = runBlocking {
        val f = Fixture(this)
        val ticket = f.driver.invalidate()
        f.read = { assertEquals("com.ss.android.ugc.aweme", it.packageName)
            BrowsePageSnapshot(it, IntRect(0, 0, 500, 1000), emptyList()) }
        val latest = f.driver.changed("com.ss.android.ugc.aweme", 2, BrowsePageEvent.WINDOW)!!
        assertNull(f.driver.changedIfCurrent(ticket, f.epoch, "com.tencent.mm", 1))
        latest.join(); assertEquals(1, f.captures)
        f.driver.close().join()
    }
    @Test fun windowHintCannotBeReboundToNewConsentEpoch() = runBlocking {
        val f = Fixture(this); val ticket = f.driver.invalidate(); val oldEpoch = f.epoch
        f.epoch++
        assertNull(f.driver.changedIfCurrent(ticket, oldEpoch, "com.tencent.mm", 1))
        assertEquals(0, f.reserves)
        f.driver.close().join()
    }
}
