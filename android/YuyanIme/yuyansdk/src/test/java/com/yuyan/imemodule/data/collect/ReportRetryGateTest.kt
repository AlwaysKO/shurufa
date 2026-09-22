package com.yuyan.imemodule.data.collect

import org.junit.Assert.*
import org.junit.Test

class ReportRetryGateTest {
    @Test fun repeatedFastImageSuccessCannotStealTheRegularThirtySecondSlot() {
        val gate = ReportRetryGate()
        for (cycle in 1..10) {
            val regularDue = cycle * 30_000L
            gate.record("online", regularDue + 500, failed = false)
            assertTrue(gate.blocks("online", regularDue, regularSync = false))
            assertFalse(gate.blocks("online", regularDue, regularSync = true))
        }
    }

    @Test fun failureStillBlocksBothKindsUntilDeadlineAndTargetsStayIndependent() {
        val gate = ReportRetryGate()
        gate.record("online", 30_000, failed = true)
        assertTrue(gate.blocks("online", 29_999, regularSync = true))
        assertTrue(gate.blocks("online", 29_999, regularSync = false))
        assertFalse(gate.blocks("online", 30_000, regularSync = true))
        assertFalse(gate.blocks("local", 1, regularSync = false))
        gate.record("online", 31_000, failed = false)
        assertFalse(gate.blocks("online", 30_001, regularSync = true))
    }

    @Test fun busyFastRunCannotConsumeAnUnexecutedRegularSyncRequest() {
        val gate = ReportRetryGate()
        // 常规周期到达时，目标仍由上一图片轮占用。
        gate.requestRegular("online")
        gate.record("online", 30_500, failed = false, regularCompleted = false)
        assertTrue(gate.regularPending("online"))
        assertFalse(gate.blocks("online", 30_000, regularSync = gate.regularPending("online")))
        gate.record("online", 31_000, failed = false, regularCompleted = true)
        assertFalse(gate.regularPending("online"))
        assertFalse(gate.regularPending("local"))
    }
}
