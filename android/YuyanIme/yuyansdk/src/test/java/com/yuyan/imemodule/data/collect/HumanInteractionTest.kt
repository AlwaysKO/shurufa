package com.yuyan.imemodule.data.collect

import org.junit.Assert.*
import org.junit.Test

class HumanInteractionTest {
    @Test fun `delayed event retains actual wall time rather than upload time`() {
        assertEquals(HumanInteraction(95_000, "touch"), humanInteractionAt(5_000, "touch", 100_000, 10_000))
    }
    @Test fun `invalid event clock or source cannot create activity`() {
        assertNull(humanInteractionAt(0, "touch", 100_000, 10_000))
        assertNull(humanInteractionAt(10_001, "touch", 100_000, 10_000))
        assertNull(humanInteractionAt(9_000, "window", 100_000, 10_000))
        assertNull(humanInteractionAt(9_000, "touch", 1, 10_000))
    }
    @Test fun `automation suppression uses event time and does not suppress subsequent user`() {
        val guard = HumanInteractionAutomationGuard()
        guard.suppress(1_000, 1_000)
        assertTrue(guard.suppressed(1_500))
        assertTrue(guard.suppressed(2_000))
        assertFalse(guard.suppressed(2_001))
        assertFalse(guard.suppressed(999))
    }
    @Test fun `restored automation history excludes delayed system events after restart`() {
        val beforeRestart = HumanInteractionAutomationHistory("", 100_000)
        val saved = beforeRestart.record(100_000)
        val afterRestart = HumanInteractionAutomationHistory(saved, 105_000)
        assertTrue(afterRestart.suppressed(100_500))
        assertFalse(afterRestart.suppressed(101_001))
    }
    @Test fun `malformed future expired and oversized automatic history is ignored`() {
        val now = 100_000_000L
        val history = HumanInteractionAutomationHistory("bad;${now + 1}:${now + 2};1:1001", now)
        assertFalse(history.suppressed(now + 1))
        assertFalse(history.suppressed(500))
        assertFalse(HumanInteractionAutomationHistory("x".repeat(8193), now).suppressed(now))
    }

}
