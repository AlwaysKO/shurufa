package com.yuyan.imemodule.service.capture
import org.junit.Assert.*
import org.junit.Test

class EmptyTreeCandidateProbeTest {
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
