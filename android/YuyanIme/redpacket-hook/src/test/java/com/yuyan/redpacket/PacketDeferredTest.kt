package com.yuyan.redpacket
import org.junit.Assert.*
import org.junit.Test

class PacketDeferredTest {
    private fun p(id: String) = Packet(id, "url", "g@chatroom", 1, 1, packetHash(id))
    @Test fun storesFourUnsentPacketsAndResumesSequentiallyWithoutReplay() {
        val port = PacketEngineTest.Port(); port.safe = false
        var now = 0L
        val engine = PacketEngine(port, 128) { now }
        (1..5).forEach { engine.accept(p("$it")) }
        engine.accept(p("1"))
        assertTrue(port.requests.isEmpty()); assertTrue(port.seen.isEmpty())
        assertEquals(4, engine.deferredCount())
        now = 60_000; port.safe = true; engine.resumeDeferred()
        assertEquals(1, port.requests.size)
        engine.receiveBusiness(port.requests[0], true, "token")
        engine.openBusiness(port.requests[1], true)
        assertEquals(3, port.requests.size)
        assertEquals(2, engine.deferredCount())
        engine.stop()
        assertEquals(0, engine.deferredCount())
    }
    @Test fun duplicateFirstDeferredItemDoesNotBlockTheNextNewPacket() {
        val port = PacketEngineTest.Port(); port.safe = false
        val engine = PacketEngine(port, 128) { 0 }
        port.seen.add(packetHash("a"))
        engine.accept(p("a")); engine.accept(p("b"))
        port.safe = true; engine.resumeDeferred()
        assertEquals(1, port.requests.size)
        assertTrue(packetHash("b") in port.seen)
        assertEquals(0, engine.deferredCount())
    }
    @Test fun protectionRaceDuringResumeKeepsOriginalExpiryAndDoesNotSpin() {
        val port = object : PacketEngineTest.Port() {
            var checks = 0
            override fun allowed() = if (!safe) false else ++checks == 1
        }
        port.safe = false
        var now = 0L; val engine = PacketEngine(port, 128) { now }
        engine.accept(p("a"))
        now = 23 * 60 * 60 * 1000L; port.safe = true
        engine.resumeDeferred()
        assertEquals(1, engine.deferredCount())
        assertEquals(60 * 60 * 1000L, engine.nextDeadlineDelay())
        assertTrue(port.requests.isEmpty())
    }
    @Test fun deferredDeadlineDoesNotRenewOnRepeatedProtectionChanges() {
        val port = PacketEngineTest.Port(); port.safe = false
        var now = 0L; val engine = PacketEngine(port, 128) { now }
        engine.accept(p("a")); now = 23 * 60 * 60 * 1000L
        engine.accept(p("a")); engine.resumeDeferred()
        now = 24 * 60 * 60 * 1000L
        port.safe = true; engine.resumeDeferred()
        assertEquals(0, engine.deferredCount()); assertTrue(port.requests.isEmpty())
    }
}
