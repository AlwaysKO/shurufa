package com.yuyan.imemodule.data.capture.page

import org.junit.Assert.*
import org.junit.Test

class BrowsingCaptureScheduleTest {
    private val a = BrowsePageToken("com.tencent.mm", 10, 1)
    private val b = BrowsePageToken("com.ss.android.ugc.aweme", 20, 2)
    @Test fun requiresChangeAndStablePage() {
        val s = BrowsingCaptureSchedule()
        assertNull(s.take(1000, a, true) { true })
        s.changed(a, 1000)
        assertNull(s.take(1799, a, true) { true })
        assertEquals(a, s.take(1800, a, true) { true })
        assertNull(s.take(500000, a, true) { true })
    }
    @Test fun burstKeepsOneLatestCandidate() {
        val s = BrowsingCaptureSchedule()
        s.changed(a, 0); s.changed(a, 500); s.changed(b, 700)
        assertNull(s.take(1499, b, true) { true })
        assertEquals(b, s.take(1500, b, true) { true })
    }
    @Test fun intervalIsSharedAcrossBothAppsAndFailedPictures() {
        val s = BrowsingCaptureSchedule()
        s.changed(a, 0)
        assertEquals(a, s.take(800, a, true) { true })
        // 取帧失败/重复也已经消耗尝试，不能退还间隔。
        s.changed(b, 900)
        assertNull(s.take(180799, b, true) { true })
        assertEquals(b, s.take(180800, b, true) { true })
    }
    @Test fun blockedInputRetainsOnlyCurrentCandidateWithoutReserving() {
        val s = BrowsingCaptureSchedule(); s.changed(a, 0)
        assertNull(s.take(800, a, false) { fail("must not reserve"); true })
        assertEquals(a, s.take(900, a, true) { true })
    }
    @Test fun leavingClearsCandidateEvenBeforeIntervalExpires() {
        val s = BrowsingCaptureSchedule(); s.changed(a, 0); s.leave(1)
        assertNull(s.take(500000, a, true) { fail("stale"); true })
        s.changed(a, 500001) // 迟到的同代次事件不得重建已离开页面。
        assertNull(s.take(501000, a, true) { true })
        s.changed(b, 501001)
        assertEquals(b, s.take(502000, b, true) { true })
    }
    @Test fun staleEventCannotReplaceNewNavigation() {
        val s = BrowsingCaptureSchedule(); s.changed(b, 1000); s.changed(a, 1100)
        assertEquals(b, s.take(1800, b, true) { true })
    }
    @Test fun currentMismatchDiscardsCandidateRatherThanPhotographingOldPage() {
        val s = BrowsingCaptureSchedule(); s.changed(a, 0)
        assertNull(s.take(800, b, true) { fail("stale"); true })
        assertNull(s.take(900, a, true) { true })
    }
    @Test fun deniedBudgetDoesNotReturnPermissionOrPollUnchangedPage() {
        val s = BrowsingCaptureSchedule(); s.changed(a, 0)
        assertNull(s.take(800, a, true) { false })
        assertNull(s.take(1000000, a, true) { fail("no change"); true })
        s.changed(b, 1000001)
        assertEquals(b, s.take(1000801, b, true) { true })
    }
    @Test fun unsupportedAppsNeverBecomeCandidates() {
        val s = BrowsingCaptureSchedule()
        val other = BrowsePageToken("com.taobao.taobao", 3, 1)
        s.changed(other, 0)
        assertNull(s.take(1000, other, true) { fail("unsupported"); true })
    }
    @Test fun reversedClockAndLateChangeCannotShortenStablePeriod() {
        val s = BrowsingCaptureSchedule(); s.changed(a, 1000); s.changed(a, 900)
        assertNull(s.take(900, a, true) { true })
        assertNull(s.take(1700, a, true) { true })
        assertEquals(a, s.take(1800, a, true) { true })
    }
    @Test fun rejectedBudgetCannotBeRetriedByAnAlreadyObservedEvent() {
        val s = BrowsingCaptureSchedule(); s.changed(a, 100)
        assertNull(s.take(900, a, true) { false })
        s.changed(a, 100)
        assertNull(s.take(1000, a, true) { fail("duplicate event reserved again"); true })
    }
    @Test fun delayedPreCaptureEventCannotReviveAConsumedPage() {
        val s = BrowsingCaptureSchedule(); s.changed(a, 100)
        assertEquals(a, s.take(900, a, true) { true })
        s.changed(a, 500)
        assertNull(s.take(181000, a, true) { fail("old change"); true })
    }
    @Test fun consumedGenerationStillRejectsAnotherWindow() {
        val s = BrowsingCaptureSchedule(); s.changed(a, 100)
        assertEquals(a, s.take(900, a, true) { true })
        val wrongWindow = a.copy(windowId = 99)
        s.changed(wrongWindow, 1000)
        assertNull(s.take(181000, wrongWindow, true) { fail("same generation changed window"); true })
    }
    @Test fun reservationCannotReenterAndGrantAnAdditionalAttempt() {
        val s = BrowsingCaptureSchedule(); s.changed(a, 100)
        var reserved = 0
        assertNull(s.take(900, a, true) {
            reserved++
            s.changed(b, 1000)
            assertNull(s.take(1800, b, true) { reserved++; true })
            true
        })
        assertEquals(1, reserved)
        assertNull(s.take(180899, b, true) { true })
        assertEquals(b, s.take(180900, b, true) { true })
    }
    @Test fun reservationExceptionDoesNotLeaveSchedulerLockedOrReuseCandidate() {
        val s = BrowsingCaptureSchedule(); s.changed(a, 100)
        assertThrows(IllegalStateException::class.java) {
            s.take(900, a, true) { throw IllegalStateException("storage failed") }
        }
        assertNull(s.take(1000, a, true) { true })
        s.changed(b, 1001)
        assertEquals(b, s.take(1801, b, true) { true })
    }
}
