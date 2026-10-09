package com.yuyan.imemodule.data.redpacket

import org.junit.Assert.*
import org.junit.Test

class SilentPacketDispatchTest {
    private val packet = PacketCandidate("a".repeat(64), "测试群", true)

    @Test fun requiresExplicitModeUserAndExactGroup() {
        assertTrue(silentPacketEligible("AUTO", 128, 128, setOf("测试群"), packet))
        assertTrue(silentPacketEligible("PROBE", 0, 0, setOf("测试群"), packet))
        assertFalse(silentPacketEligible("OFF", 128, 128, setOf("测试群"), packet))
        assertFalse(silentPacketEligible("AUTO", -1, 128, setOf("测试群"), packet))
        assertFalse(silentPacketEligible("AUTO", 128, 0, setOf("测试群"), packet))
        assertFalse(silentPacketEligible("AUTO", 128, 128, setOf("测试"), packet))
    }

    @Test fun persistsBeforeAcceptingAndRefusesUnknownReplayAfterRestart() {
        var saved = ""
        val first = SilentPacketLedger("") { saved = it; true }
        assertTrue(first.reserve(packet.id, 100))
        assertFalse(first.reserve(packet.id, 101))
        assertFalse(SilentPacketLedger(saved) { true }.reserve(packet.id, 102))
    }

    @Test fun failedPersistenceNeverAuthorizesLaunch() {
        assertFalse(SilentPacketLedger("") { false }.reserve(packet.id, 100))
        assertFalse(SilentPacketLedger("") { true }.reserve("invalid", 100))
    }

    @Test fun boundedLedgerKeepsNewestAndRejectsClockRollback() {
        var saved = ""
        val ledger = SilentPacketLedger("") { saved = it; true }
        repeat(140) { assertTrue(ledger.reserve(it.toString(16).padStart(64, '0'), it.toLong() + 100)) }
        assertEquals(128, saved.lines().size)
        assertFalse(SilentPacketLedger(saved) { true }.reserve("0".repeat(64), 1))
    }
}
