package com.yuyan.redpacket

import org.junit.Assert.*
import org.junit.Test

class PacketCoreTest {
    private val url = "wxpay://c2cbizmessagehandler/hongbao/receivehongbao?sendid=abc&msgtype=1&channelid=1"
    private fun message(type: Int = 436207665, sent: Int? = 0, group: String = "g@chatroom", xml: String = "<msg><appmsg><wcpayinfo><nativeurl><![CDATA[$url]]></nativeurl></wcpayinfo></appmsg></msg>") =
        StoredMessage(type, sent, group, xml, 1000)

    @Test fun acceptsOnlySuccessfulInboundOrdinaryGroupPackets() {
        assertNotNull(PacketParser.parse(message(), 1, 1000))
        assertNull(PacketParser.parse(message(), -1, 1000))
        assertNull(PacketParser.parse(message(sent = null), 1, 1000))
        assertNull(PacketParser.parse(message(sent = 1), 1, 1000))
        assertNull(PacketParser.parse(message(type = 469762097), 1, 1000))
        assertNull(PacketParser.parse(message(group = "friend"), 1, 1000))
        assertNull(PacketParser.parse(message(type = 1), 1, 1000))
    }
    @Test fun rejectsOldFutureMalformedAndDuplicateUrlParameters() {
        assertNull(PacketParser.parse(message(), 1, 100_000))
        assertNull(PacketParser.parse(message(), 1, 999))
        assertNull(PacketParser.parse(message(xml = "<msg><nativeurl>$url</nativeurl>"), 1, 1000))
        assertNull(PacketParser.parse(message(xml = "<!DOCTYPE msg [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><msg>&x;</msg>"), 1, 1000))
        assertNull(PacketParser.parse(message(xml = "<msg><appmsg><wcpayinfo><nativeurl>${url.replace("&", "&amp;")}&amp;sendid=other</nativeurl></wcpayinfo></appmsg></msg>"), 1, 1000))
    }
    @Test fun rejectsEncodedSendIdParameterInjection() {
        val evil = url.replace("sendid=abc", "sendid=abc%26extra%3Dvalue")
        val xml = "<msg><appmsg><wcpayinfo><nativeurl><![CDATA[$evil]]></nativeurl></wcpayinfo></appmsg></msg>"
        assertNull(PacketParser.parse(message(xml = xml), 1, 1000))
    }
    @Test fun parsesSenderPrefixAndHashDoesNotIncludePlainCredentials() {
        val xml = "sender:\n<msg><appmsg><wcpayinfo><nativeurl><![CDATA[$url]]></nativeurl></wcpayinfo></appmsg></msg>"
        val p = PacketParser.parse(message(xml = xml), 1, 1000)!!
        assertEquals("abc", p.sendId)
        assertEquals(64, p.key.length)
        assertFalse(p.toString().contains("abc"))
        assertFalse(p.toString().contains("g@chatroom"))
    }
    @Test fun scopeRejectsUnknownUserVersionProcessAndEmptyWhitelist() {
        val config = PacketConfig(Mode.AUTO, 128, setOf("g@chatroom"))
        assertTrue(config.acceptHost("com.tencent.mm", "com.tencent.mm", 128, "8.0.78", 3180))
        assertFalse(config.acceptHost("com.tencent.mm", "com.tencent.mm", 0, "8.0.78", 3180))
        assertFalse(config.acceptHost("com.tencent.mm", "com.tencent.mm:tools", 128, "8.0.78", 3180))
        assertFalse(config.acceptHost("com.tencent.mm", "com.tencent.mm", 128, "8.0.79", 3180))
        assertFalse(config.copy(groups = emptySet()).permits("g@chatroom", true))
        assertFalse(config.copy(mode = Mode.PROBE).permits("g@chatroom", true))
        assertFalse(config.permits("g@chatroom", false))
    }
    @Test fun doesNotAdvanceUntilSendAndBothCallbacksSucceedForExactRequest() {
        val flow = PacketFlow(1000)
        val request = Any()
        val other = Any()
        assertTrue(flow.beginReceive(request, 1000))
        assertFalse(flow.receiveBusiness(other, true, "token", 1001))
        assertTrue(flow.receiveBusiness(request, true, "token", 1001))
        assertFalse(flow.canOpen(1001))
        flow.transport(request, true, 1001)
        assertFalse(flow.canOpen(1001))
        flow.sent(request, true, 1001)
        assertTrue(flow.canOpen(1001))
        val open = Any()
        assertTrue(flow.beginOpen(open, 1001))
        flow.openBusiness(open, true, 1002)
        flow.transport(open, true, 1002)
        assertEquals(FlowState.OPENING, flow.state)
        flow.sent(open, true, 1002)
        assertEquals(FlowState.CLAIMED, flow.state)
        assertFalse(flow.beginOpen(Any(), 1002))
    }
    @Test fun timeoutSendFailureAndClosureNeverReplay() {
        for (duringOpen in listOf(false, true)) {
            val flow = PacketFlow(1000)
            val receive = Any()
            flow.beginReceive(receive, 1000)
            flow.sent(receive, true, 1001)
            flow.receiveBusiness(receive, true, "token", 1001)
            flow.transport(receive, true, 1001)
            if (duringOpen) flow.beginOpen(Any(), 1001)
            flow.expire(13_000)
            assertEquals(FlowState.UNKNOWN, flow.state)
            assertFalse(flow.canOpen(13_000))
            assertFalse(flow.beginReceive(Any(), 13_000))
        }
        val flow = PacketFlow(0)
        val req = Any()
        flow.beginReceive(req, 0)
        flow.sent(req, false, 1)
        flow.transport(req, true, 2)
        flow.receiveBusiness(req, true, "token", 2)
        assertEquals(FlowState.FAILED, flow.state)
        assertFalse(flow.canOpen(2))
    }
    @Test fun rejectionAndOutOfOrderCallbackCannotReportClaimed() {
        val flow = PacketFlow(0)
        val req = Any()
        flow.beginReceive(req, 0)
        flow.sent(req, true, 1)
        flow.receiveBusiness(req, false, "", 1)
        flow.transport(req, true, 1)
        assertEquals(FlowState.REJECTED, flow.state)
        assertFalse(flow.canOpen(1))
    }
}
