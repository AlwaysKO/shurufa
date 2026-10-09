package com.yuyan.redpacket
import org.junit.Assert.*
import org.junit.Test

class PacketEngineTest {
    open class Port : PacketPort {
        var settings = PacketConfig(Mode.AUTO, 128, setOf("g@chatroom"))
        var safe = true
        val seen = mutableSetOf<String>()
        val requests = mutableListOf<Any>()
        val reports = mutableListOf<String>()
        var sendOk = true
        override fun config() = settings
        override fun allowed() = safe
        override fun reserve(key: String) = seen.add(key)
        override fun receive(packet: Packet) = Any()
        override fun open(packet: Packet, token: String) = Any()
        override fun send(request: Any, callback: (Any, Boolean) -> Unit): Boolean { requests.add(request); callback(request, true); return sendOk }
        override fun report(status: String) { reports.add(status) }
    }
    private fun packet(id: String) = Packet(id, "url", "g@chatroom", 1, 1, packetHash(id))
    @Test fun twoPacketsOutOfOrderKeepCorrectRequestAssociationAndDeduplicate() {
        val port = Port(); val engine = PacketEngine(port, 128) { 0 }
        engine.accept(packet("a")); engine.accept(packet("b")); engine.accept(packet("a"))
        assertEquals(2, port.requests.size)
        val a = port.requests[0]; val b = port.requests[1]
        engine.receiveBusiness(b, true, "b-token")
        assertEquals(3, port.requests.size)
        engine.receiveBusiness(a, true, "a-token")
        assertEquals(4, port.requests.size)
        engine.openBusiness(port.requests[2], true)
        engine.openBusiness(port.requests[3], true)
        assertEquals(2, port.reports.count { it == "claimed" })
        engine.receiveBusiness(b, true, "b-token")
        assertEquals(4, port.requests.size)
    }
    @Test fun protectionBetweenReceiveAndOpenBlocksSecondRequest() {
        val port = Port(); val engine = PacketEngine(port, 128) { 0 }
        engine.accept(packet("a")); port.safe = false
        engine.receiveBusiness(port.requests[0], true, "token")
        assertEquals(1, port.requests.size)
        assertTrue("unknown" in port.reports)
    }
    @Test fun probeAndWrongScopeNeverSendOrReserve() {
        val port = Port(); val engine = PacketEngine(port, 128) { 0 }
        port.settings = port.settings.copy(mode = Mode.PROBE)
        engine.accept(packet("a")); engine.accept(packet("a"))
        assertEquals(1, port.reports.count { it == "probe" })
        assertTrue(port.requests.isEmpty()); assertTrue(port.seen.isEmpty())
        port.settings = port.settings.copy(mode = Mode.AUTO, user = 0)
        engine.accept(packet("b"))
        assertTrue(port.requests.isEmpty())
    }
    @Test fun failedSendCannotOpenEvenWithSynchronousSuccessfulCallback() {
        val port = Port(); port.sendOk = false
        val engine = PacketEngine(port, 128) { 0 }
        engine.accept(packet("a"))
        engine.receiveBusiness(port.requests[0], true, "token")
        assertEquals(1, port.requests.size)
        assertTrue("failed" in port.reports)
    }
    @Test fun timeoutAndDisableDoNotReplayAfterLateCallbacks() {
        var now = 0L
        val port = Port(); val engine = PacketEngine(port, 128) { now }
        engine.accept(packet("a")); now = 12000
        engine.expire()
        engine.receiveBusiness(port.requests[0], true, "token")
        engine.accept(packet("a"))
        assertEquals(1, port.requests.size)
        engine.accept(packet("b")); engine.stop()
        engine.receiveBusiness(port.requests[1], true, "token")
        assertEquals(2, port.requests.size)
    }
}
