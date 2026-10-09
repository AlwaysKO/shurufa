package com.yuyan.imemodule.data.capture.page

import org.junit.Assert.*
import org.junit.Test

class VideoVisitTrackerTest {
    private fun tracker() = VideoVisitTracker()
    @Test fun missingImagesDoNotLoseForegroundDuration() {
        val t = tracker()
        t.enter("wechat", "video-a", 100, 1000)
        val result = t.finish(t.snapshot()!!.id, 43100, 44000, VideoExitReason.BACKGROUND)!!
        assertEquals(43000L, result.durationMillis)
        assertNull(result.firstImage)
        assertNull(result.lastImage)
        assertTrue(result.complete)
    }
    @Test fun repeatedObservationDoesNotRestartVisitOrRequestNewFirstFrame() {
        val t = tracker()
        val first = t.enter("wechat", "a", 100, 1000)
        val again = t.enter("wechat", "a", 200, 2000)
        assertEquals(first.active.id, again.active.id)
        assertFalse(again.started)
        assertNull(again.previous)
        assertEquals(100L, t.finish(t.snapshot()!!.id, 200, 2000, VideoExitReason.EXIT)!!.durationMillis)
    }
    @Test fun videoChangeEndsOldVisitAndRejectsLateOldFrame() {
        val t = tracker()
        val first = t.enter("wechat", "a", 100, 1000).active
        val next = t.enter("wechat", "b", 400, 1300)
        assertEquals(300L, next.previous!!.durationMillis)
        assertNotEquals(first.id, next.active.id)
        assertFalse(t.attachFrame(first.id, "a", VideoFrameRole.LAST, "old-frame"))
        assertNull(t.finish(t.snapshot()!!.id, 500, 1400, VideoExitReason.LOCKED)!!.lastImage)
    }
    @Test fun returningToSameVideoCreatesAnotherVisit() {
        val t = tracker()
        val first = t.enter("douyin", "a", 100, 1000).active
        t.finish(t.snapshot()!!.id, 200, 1100, VideoExitReason.EXIT)
        assertNotEquals(first.id, t.enter("douyin", "a", 300, 1200).active.id)
    }
    @Test fun platformChangeMustEndVisitEvenWhenVideoKeysMatch() {
        val t = tracker()
        t.enter("wechat", "a", 100, 1000)
        val change = t.enter("douyin", "a", 300, 1200)
        assertTrue(change.started)
        assertEquals("wechat", change.previous!!.platform)
        assertEquals("douyin", change.active.platform)
    }
    @Test fun clockCorrectionDoesNotChangeElapsedDuration() {
        val t = tracker()
        t.enter("douyin", "a", 100, 10000)
        assertEquals(900L, t.finish(t.snapshot()!!.id, 1000, 5000, VideoExitReason.EXIT)!!.durationMillis)
    }
    @Test fun invalidMonotonicClockNeverFabricatesDuration() {
        val t = tracker()
        t.enter("douyin", "a", 100, 1000)
        val result = t.finish(t.snapshot()!!.id, 90, 1010, VideoExitReason.EXIT)!!
        assertFalse(result.complete)
        assertNull(result.durationMillis)
    }
    @Test fun recoveryNeverCountsProcessAbsenceAsViewing() {
        val t = tracker()
        val saved = t.enter("wechat", "a", 100, 1000).active
        val interrupted = t.interrupted(saved)
        assertFalse(interrupted.complete)
        assertNull(interrupted.endedAt)
        assertNull(interrupted.durationMillis)
        assertEquals(VideoExitReason.INTERRUPTED, interrupted.reason)
    }
    @Test fun framesRequireMatchingVisitAndVideoAndKeepFirstFrame() {
        val t = tracker()
        val visit = t.enter("wechat", "a", 100, 1000).active
        assertFalse(t.attachFrame(visit.id, "b", VideoFrameRole.FIRST, "wrong"))
        assertTrue(t.attachFrame(visit.id, "a", VideoFrameRole.FIRST, "first"))
        assertFalse(t.attachFrame(visit.id, "a", VideoFrameRole.FIRST, "second"))
        assertTrue(t.attachFrame(visit.id, "a", VideoFrameRole.LAST, "last"))
        val ended = t.finish(t.snapshot()!!.id, 200, 1100, VideoExitReason.SWITCHED)!!
        assertEquals("first", ended.firstImage)
        assertEquals("last", ended.lastImage)
        assertNull(t.finish(visit.id, 300, 1200, VideoExitReason.EXIT))
    }

    @Test fun staleEndCallbackCannotFinishNextVideo() {
        val t = tracker()
        val old = t.enter("wechat", "a", 100, 1000).active
        val current = t.enter("wechat", "b", 200, 1100).active
        assertNull(t.finish(old.id, 300, 1200, VideoExitReason.BACKGROUND))
        assertEquals(current.id, t.snapshot()!!.id)
    }
    @Test fun endOlderThanLastObservationIsNotACompleteDuration() {
        val t = tracker()
        t.enter("wechat", "a", 100, 1000)
        val visit = t.enter("wechat", "a", 1000, 1900).active
        val result = t.finish(visit.id, 500, 1400, VideoExitReason.EXIT)!!
        assertFalse(result.complete)
        assertNull(result.durationMillis)
    }
    @Test fun recoveryPreservesAnAlreadyFinishedRecord() {
        val t = tracker()
        val visit = t.enter("wechat", "a", 100, 1000).active
        val result = t.finish(visit.id, 500, 1400, VideoExitReason.EXIT)!!
        assertEquals(result, t.interrupted(result))
    }
}
