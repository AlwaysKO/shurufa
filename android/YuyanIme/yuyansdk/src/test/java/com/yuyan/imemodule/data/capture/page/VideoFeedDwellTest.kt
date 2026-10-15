package com.yuyan.imemodule.data.capture.page

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class VideoFeedDwellTest {
    @Test fun waitsOnlyRemainingTimeAndIncludesInitialObservationDelay() = runBlocking {
        var now = 1800L
        val waits = mutableListOf<Long>()
        assertTrue(awaitVideoFeedDwell(1000, { now }, { true }) { waits += it; now += it })
        assertEquals(listOf(2200L), waits)
        assertEquals(4000L, now)
    }

    @Test fun exactlyThreeSecondsIsEligibleWithoutFurtherDelay() = runBlocking {
        assertTrue(awaitVideoFeedDwell(1000, { 4000 }, { true }) { error("already due") })
    }

    @Test fun swipeBeforeThreeSecondsInvalidatesOldCandidate() = runBlocking {
        var now = 1800L
        var current = true
        assertFalse(awaitVideoFeedDwell(1000, { now }, { current }) { now += it; current = false })
    }

    @Test fun invalidCandidateAndClockRollbackCannotAuthorizeCapture() = runBlocking {
        assertFalse(awaitVideoFeedDwell(1000, { 1800 }, { false }) { error("invalid candidate") })
        assertFalse(awaitVideoFeedDwell(1000, { 999 }, { true }) { error("invalid clock") })
        assertFalse(awaitVideoFeedDwell(1000, { 1800 }, { true }) { })
    }

    @Test fun cancellingWhileWaitingNeverReachesCapture() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var captured = false
        val job = launch {
            if (awaitVideoFeedDwell(1000, { 1800 }, { true }) { started.complete(Unit); awaitCancellation() })
                captured = true
        }
        started.await(); job.cancelAndJoin()
        assertFalse(captured)
    }
}
