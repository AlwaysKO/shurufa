package com.yuyan.imemodule.data.redpacket
import org.junit.Assert.*
import org.junit.Test

class PacketInitialLayoutTest {
    @Test fun onlyOneStationaryNewWindowEventFollowsOwnNavigation() {
        val receipt = PacketInitialLayout()
        receipt.expect(10, 100)
        assertTrue(receipt.consume(11, 2100, true))
        assertFalse(receipt.consume(11, 2101, true))
    }
    @Test fun movementSameWindowExpiredAndUnsolicitedEventsAreRejected() {
        assertFalse(PacketInitialLayout().consume(11, 500, true))
        for ((window, time, stationary) in listOf(Triple(10, 200L, true), Triple(11, 200L, false),
            Triple(11, 2601L, true), Triple(11, 99L, true))) {
            val receipt = PacketInitialLayout(); receipt.expect(10, 100)
            assertFalse(receipt.consume(window, time, stationary))
        }
    }
    @Test fun cancellationRemovesExpectedNavigation() {
        val receipt = PacketInitialLayout(); receipt.expect(10, 100); receipt.clear()
        assertFalse(receipt.consume(11, 200, true))
    }
}
