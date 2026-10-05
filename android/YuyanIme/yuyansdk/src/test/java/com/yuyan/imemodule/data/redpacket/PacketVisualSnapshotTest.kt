package com.yuyan.imemodule.data.redpacket

import com.yuyan.imemodule.data.capture.ui.IntRect
import org.junit.Assert.*
import org.junit.Test

class PacketVisualSnapshotTest {
    private val target = IntRect(80,300,300,450)
    private fun sample(chat: String = "测试群", id: String = "visual:card:80,300,300,450", rect: IntRect = target) =
        PacketVisualSnapshot(PacketVisualMatch(PacketPage(chat, true, listOf(id)), mapOf(id to rect)),
            42, IntRect(0,30,500,1000), 0,30,1000,mapOf(id to "pixels"))
    @Test fun stableTargetMapsFromWindowPixelsToScreen() {
        val first = sample()
        assertEquals(IntRect(80,330,300,480), confirmedPacketTarget(first,first,first.match.page.cards.single(),1100))
    }
    @Test fun smallRecognitionJitterIsAllowedButMovedCardIsNot() {
        val first = sample()
        assertNotNull(confirmedPacketTarget(first,sample(id="visual:card:jitter", rect=IntRect(82,302,302,452)),first.match.page.cards.single(),1100))
        assertNull(confirmedPacketTarget(first,sample(rect=IntRect(80,400,300,550)),first.match.page.cards.single(),1100))
    }
    @Test fun differentChatWindowOriginOrStaleCaptureIsRejected() {
        val first = sample(); val id = first.match.page.cards.single()
        for (other in listOf(sample(chat="另一群"),first.copy(windowId=43),first.copy(originY=0),first.copy(capturedAt=0)))
            assertNull(confirmedPacketTarget(first,other,id,2500))
    }
    @Test fun changedCardContentOrNewOverlayIsRejected() {
        val first = sample(); val id=first.match.page.cards.single()
        assertNull(confirmedPacketTarget(first,first.copy(signatures=mapOf(id to "changed")),id,1100))
        assertNull(confirmedPacketTarget(first,first.copy(match=PacketVisualMatch(PacketPage(packetPanel=true),mapOf(id to target))),id,1100))
    }
}
