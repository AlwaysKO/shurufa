package com.yuyan.imemodule.service.capture
import org.junit.Assert.*
import org.junit.Test

class EmptyTreeCandidateProbeTest {
    @Test fun recoveryDelayFollowsActualAttemptAndStopsAfterBudgetOrNavigation() {
        var now = 5_000L
        val probe = EmptyTreeCandidateProbe { now }
        val scope = ScreenshotScope(1, 1)
        assertNull(probe.retryDelayMillis(scope))
        assertTrue(probe.consume(scope))
        assertEquals(2_000L, probe.retryDelayMillis(scope))
        now += 900
        assertEquals(1_100L, probe.retryDelayMillis(scope))
        assertNull(probe.retryDelayMillis(ScreenshotScope(1, 2)))
        now += 1_100
        assertEquals(0L, probe.retryDelayMillis(scope))
        assertTrue(probe.consume(scope))
        now += 2_000
        assertTrue(probe.consume(scope))
        assertNull(probe.retryDelayMillis(scope))
        probe.clear()
        assertNull(probe.retryDelayMillis(scope))
    }
    @Test fun firstUnconfirmedFailureAllowsOnlySpacedFiniteRecovery() {
        var now = 0L
        val probe = EmptyTreeCandidateProbe { now }
        val scope = ScreenshotScope(1, 1)
        assertTrue(probe.consume(scope))
        repeat(100) { assertFalse(probe.consume(scope)) }
        now += 2000
        assertTrue(probe.consume(scope))
        now += 2000
        assertTrue(probe.consume(scope))
        now += 60000
        assertFalse(probe.consume(scope))
        assertTrue(probe.consume(ScreenshotScope(1, 2)))
        probe.clear()
        assertTrue(probe.consume(scope))
    }
}
