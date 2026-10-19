package com.yuyan.imemodule.data.collect

import org.junit.Assert.*
import org.junit.Test

class StepMovementSignalTest {
    private val second = 1_000_000_000L
    @Test fun twoFreshIndependentStepsCanRequestAProbe() {
        val gate = StepMovementSignal(100 * second)
        assertFalse(gate.observe(101 * second, 101 * second, 1f))
        assertTrue(gate.observe(102 * second, 102 * second, 1f))
    }
    @Test fun cachedDuplicateOutOfOrderAndInvalidEventsCannotTrigger() {
        val gate = StepMovementSignal(100 * second)
        assertFalse(gate.observe(99 * second, 101 * second, 1f))
        assertFalse(gate.observe(101 * second, 112 * second, 1f))
        assertFalse(gate.observe(113 * second, 112 * second, 1f))
        assertFalse(gate.observe(112 * second, 112 * second, Float.NaN))
        assertFalse(gate.observe(112 * second, 112 * second, 0f))
        assertFalse(gate.observe(112 * second, 112 * second, 1f))
        assertFalse(gate.observe(112 * second, 112 * second, 1f))
        assertFalse(gate.observe(111 * second, 112 * second, 1f))
        assertTrue(gate.observe(113 * second, 113 * second, 1f))
    }
    @Test fun widelySeparatedOrNewSessionStepsCannotBorrowOldEvidence() {
        val gate = StepMovementSignal(100 * second)
        assertFalse(gate.observe(101 * second, 101 * second, 1f))
        assertFalse(gate.observe(117 * second, 117 * second, 1f))
        assertTrue(gate.observe(118 * second, 118 * second, 1f))
        assertFalse(StepMovementSignal(119 * second).observe(120 * second, 120 * second, 1f))
    }
}
