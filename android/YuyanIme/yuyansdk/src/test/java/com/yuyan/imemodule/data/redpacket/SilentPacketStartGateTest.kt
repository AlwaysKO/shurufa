package com.yuyan.imemodule.data.redpacket

import org.junit.Assert.assertEquals
import org.junit.Test

class SilentPacketStartGateTest {
    @Test fun lateClickExtendsQuietWindowFromLatestAction() {
        val gate = SilentPacketStartGate()
        gate.defer(1_000)
        assertEquals(100L, gate.remaining(1_500))
        gate.defer(1_500)
        assertEquals(600L, gate.remaining(1_500))
        assertEquals(500L, gate.remaining(1_600))
        assertEquals(1L, gate.remaining(2_099))
        assertEquals(0L, gate.remaining(2_100))
    }

    @Test fun permitsStartExactlyAfter600Milliseconds() {
        val gate = SilentPacketStartGate()
        gate.defer(0)
        assertEquals(600L, gate.remaining(0))
        assertEquals(1L, gate.remaining(599))
        assertEquals(0L, gate.remaining(600))
    }

    @Test fun expiredWindowDoesNotIntroduceAnotherPollingDelay() {
        val gate = SilentPacketStartGate()
        assertEquals(0L, gate.remaining(0))
        gate.defer(100)
        assertEquals(0L, gate.remaining(701))
        assertEquals(0L, gate.remaining(702))
        assertEquals(0L, gate.remaining(10_000))
        gate.defer(10_000)
        assertEquals(600L, gate.remaining(10_000))
    }
}
