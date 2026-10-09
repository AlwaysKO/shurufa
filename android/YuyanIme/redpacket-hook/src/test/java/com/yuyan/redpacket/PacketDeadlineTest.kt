package com.yuyan.redpacket
import org.junit.Assert.*
import org.junit.Test

class PacketDeadlineTest {
    @Test fun schedulesOnlyWhileTasksAreActiveAtTheirActualDeadline() {
        var now = 100L
        val port = PacketEngineTest.Port()
        val engine = PacketEngine(port, 128) { now }
        assertNull(engine.nextDeadlineDelay())
        engine.accept(Packet("a", "url", "g@chatroom", 1, 1, packetHash("a")))
        assertEquals(12000L, engine.nextDeadlineDelay())
        now = 300L
        assertEquals(11800L, engine.nextDeadlineDelay())
        now = 12100L
        engine.expire()
        assertNull(engine.nextDeadlineDelay())
    }
}
