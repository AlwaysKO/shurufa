package com.yuyan.imemodule.data.redpacket

import com.yuyan.imemodule.data.capture.ui.IntRect
import org.junit.Assert.*
import org.junit.Test

class NativePacketPageParserTest {
    private val ticket = NativePacketTicket(1, 100, 8, 128, 1000, 2000)
    private val root = NativePacketNode("0", null, IntRect(0, 0, 1000, 2000), windowId=7)
    private fun node(path: String, text: String = "", box: IntRect = IntRect(100, 300, 700, 380),
        parent: String = "0", click: Boolean = false, desc: String = "") =
        NativePacketNode(path, parent, box, text, desc, click, windowId=7)
    private fun window(nodes: List<NativePacketNode>) = NativePacketWindow(8, 128, 7, root.bounds, listOf(root) + nodes)
    private fun chat(name: String = "Full Group", info: String = "聊天信息(3)") = listOf(
        node("0/0", name, IntRect(220, 50, 700, 100)),
        node("0/1", box = IntRect(850, 50, 950, 110), click = true, desc = info))
    private fun parsed(nodes: List<NativePacketNode>, kind: NativePacketUi = NativePacketUi.NONE) = parseNativePacketPage(ticket.copy(packetUi=kind), window(nodes))!!
    @Test fun fullNativeTitleIsPreservedAndSeparateInfoIconIsUsed() {
        val s = parsed(chat("Full Group(3)"))
        // Bracketed digits may be part of the real name: only details can prove the notification name.
        assertEquals("Full Group(3)", s.page.chatName); assertTrue(s.page.groupChat)
        assertEquals("node:info", s.page.chatInfoButton); assertTrue(s.signatures["node:info"]!!.isNotBlank())
    }
    @Test fun literalNumericParenthesesAreNotStrippedWithoutIndependentCount() {
        assertEquals("Group(2026)", parsed(chat("Group(2026)", "聊天信息")).page.chatName)
    }
    @Test fun duplicateToolbarIsUnknown() {
        assertNull(parsed(chat() + node("0/9", "Other", IntRect(220, 50, 700, 100))).page.chatName)
    }
    @Test fun incomingCardUsesClickableAncestorPath() {
        val s = parsed(chat() + listOf(node("0/2", box=IntRect(100, 400, 600, 600), click=true),
            node("0/2/0", "微信红包", IntRect(140, 520, 500, 570), "0/2")))
        assertEquals(listOf("node:card:0/2"), s.page.cards)
        assertEquals(IntRect(100,400,600,600), s.targets[s.page.cards.single()])
    }
    @Test fun claimedExpiredExclusiveAndSentCardsAreRejected() {
        for (reject in listOf("已领取", "已被领完", "已过期", "专属红包", "已退还")) {
            val s=parsed(chat()+listOf(node("0/2",box=IntRect(100,400,600,600),click=true),
                node("0/2/0","微信红包",parent="0/2"),node("0/2/1",reject,parent="0/2")))
            assertTrue(s.page.cards.isEmpty())
        }
        val s=parsed(chat()+listOf(node("0/2",box=IntRect(500,400,950,600),click=true),node("0/2/0","微信红包",parent="0/2")))
        assertTrue(s.page.cards.isEmpty())
    }
    @Test fun onlyUniqueOpenInPacketPanelIsEligible() {
        val nodes=listOf(node("0/0","发了一个红包"),node("0/1","开",IntRect(400,900,600,1100),click=true))
        assertEquals("node:open",parsed(nodes,NativePacketUi.RECEIVE).page.openButton)
        assertNull(parsed(nodes+node("0/2","开",IntRect(400,1200,600,1400),click=true),NativePacketUi.RECEIVE).page.openButton)
        assertNull(parsed(chat()+listOf(node("0/4","发了一个红包"),node("0/5","开",IntRect(400,900,600,1100),click=true))).page.openButton)
    }
    @Test fun realDepositRequiresSenderAndAmountEvidence() {
        assertNull(parsed(listOf(node("0/1","已存入零钱"))).page.result)
        val s=parsed(listOf(node("0/1","Sender的红包",IntRect(250,300,750,350)),node("0/2","1.00元",IntRect(350,400,650,460)),node("0/3","已存入零钱",IntRect(250,500,750,550))),NativePacketUi.DETAIL)
        assertEquals("已领取",s.page.result);assertTrue(s.page.packetPanel)
    }
    @Test fun detailsUsesSeparateNativeValueWithoutChevronConcatenation() {
        val s=parsed(listOf(node("0/0","聊天信息(3)",IntRect(300,40,700,100)),
            node("0/1","群聊名称",IntRect(50,600,250,650)),node("0/2","Full Group",IntRect(650,600,920,650)),
            node("0/3",">",IntRect(950,600,980,650)),node("0/4","群公告",IntRect(50,800,250,850))))
        assertTrue(s.page.verifiedGroupDetails);assertEquals("Full Group",s.page.groupDetailsName)
        assertEquals(3,s.page.groupDetailsMemberCount)
    }
    @Test fun invalidSurfaceOrMixedPackageFailsClosed() {
        val w=window(chat())
        for(bad in listOf(w.copy(displayId=0),w.copy(displayId=9),w.copy(ownerUserId=0),
            w.copy(application=false),w.copy(extraWindows=true),w.copy(packageName="other"),
            w.copy(nodes=w.nodes+node("0/9").copy(packageName="other")),
            w.copy(nodes=w.nodes+node("0/9").copy(windowId=9)))) assertNull(parseNativePacketPage(ticket,bad))
    }
    @Test fun emptyDuplicateDeepOversizeAndBrokenParentTreesFailClosed() {
        assertNull(parseNativePacketPage(ticket,window(emptyList()).copy(nodes=emptyList())))
        assertNull(parseNativePacketPage(ticket,window(listOf(node("0/0"),node("0/0")))))
        assertNull(parseNativePacketPage(ticket,window(listOf(node("0/1",parent="absent")))))
        assertNull(parseNativePacketPage(ticket,window(listOf(node("0/1","x".repeat(2049))))))
        assertNull(parseNativePacketPage(ticket,window((0..600).map { node("0/$it") })))
        assertNull(parseNativePacketPage(ticket,window(listOf(node("0/"+"1/".repeat(31)+"1")))))
    }
    @Test fun unknownPageHasNoTargetsAndDoesNotLeakSignatureText() {
        val s=parsed(listOf(node("0/1","PrivateName")))
        assertNull(s.page.chatName);assertTrue(s.targets.isEmpty())
        assertFalse(parsed(chat()).signatures.values.any { it.contains("Full Group") })
    }
    @Test fun systemDecorationBlockedTextCannotProveToolbarOrTargets() {
        val blocked=window(chat()).copy(blockedBounds=listOf(IntRect(0,0,1000,120)))
        val s=parseNativePacketPage(ticket,blocked)!!
        assertNull(s.page.chatName);assertTrue(s.targets.isEmpty());assertEquals(blocked.blockedBounds,s.blockedBounds)
    }
    @Test fun cardIntersectingSystemDecorationIsNotEligible() {
        val nodes=chat()+listOf(node("0/2",box=IntRect(100,1800,600,1990),click=true),
            node("0/2/0","微信红包",IntRect(140,1840,500,1900),"0/2"))
        val s=parseNativePacketPage(ticket,window(nodes).copy(blockedBounds=listOf(IntRect(0,1900,1000,2000))))!!
        assertTrue(s.page.cards.isEmpty())
    }
    @Test fun resultWordsInsideEditableChatAreNotReceiptEvidence() {
        val nodes=listOf(node("0/1","Sender的红包",IntRect(250,300,750,350)),
            node("0/2","1.00元",IntRect(350,400,650,460)),node("0/3","已存入零钱",IntRect(250,500,750,550)),
            node("0/4",box=IntRect(100,1800,800,1900)).copy(editable=true))
        assertNull(parsed(nodes).page.result)
    }
    @Test fun ambiguousChatToolbarCannotTurnOrdinaryOpenTextIntoPacketPanel() {
        val nodes=chat()+listOf(node("0/9","Other",IntRect(220,50,700,100)),
            node("0/2","微信红包"),node("0/3","开",IntRect(400,900,600,1100),click=true))
        assertFalse(parsed(nodes).page.packetPanel)
    }
    @Test fun backendNativeAreaBoundaryIsNotResizedOrRejected() {
        for ((width,height) in listOf(2001 to 4000,2160 to 3840,2048 to 4096)) {
            val t=ticket.copy(width=width,height=height);val b=IntRect(0,0,width,height)
            assertNotNull(parseNativePacketPage(t,window(emptyList()).copy(bounds=b,nodes=listOf(root.copy(bounds=b)))))
        }
        val t=ticket.copy(width=2049,height=4096);val b=IntRect(0,0,2049,4096)
        assertNull(parseNativePacketPage(t,window(emptyList()).copy(bounds=b,nodes=listOf(root.copy(bounds=b)))))
    }
    @Test fun packetActivityProofIsRequiredForOpenAndClaimed() {
        val panel=listOf(node("0/1","发了一个红包"),node("0/2","开",IntRect(400,900,600,1100),click=true))
        assertNull(parsed(panel).page.openButton)
        val receipt=listOf(node("0/1","Sender的红包",IntRect(250,300,750,350)),
            node("0/2","1.00元",IntRect(350,400,650,460)),node("0/3","已存入零钱",IntRect(250,500,750,550)))
        assertNull(parsed(receipt).page.result)
        assertNull(parsed(receipt,NativePacketUi.RECEIVE).page.result)
        assertEquals("已领取",parsed(receipt,NativePacketUi.DETAIL).page.result)
    }
    @Test fun realExpiredOrExhaustedStatesNeedPacketActivityAndContext() {
        for((label,result) in listOf("该红包已过期" to "已过期","手慢了，红包派完了" to "已被领完")) {
            val nodes=listOf(node("0/1","红包详情",IntRect(300,50,700,100)),node("0/2",label))
            assertNull(parsed(nodes).page.result)
            assertEquals(result,parsed(nodes,NativePacketUi.RECEIVE).page.result)
            assertEquals(result,parsed(nodes,NativePacketUi.DETAIL).page.result)
        }
    }
}
