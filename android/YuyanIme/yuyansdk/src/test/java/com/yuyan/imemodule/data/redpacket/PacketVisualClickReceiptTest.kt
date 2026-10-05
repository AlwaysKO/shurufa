package com.yuyan.imemodule.data.redpacket

import com.yuyan.imemodule.data.capture.ui.IntRect
import org.junit.Assert.*
import org.junit.Test

class PacketVisualClickReceiptTest {
    private val target = IntRect(100, 200, 200, 300)
    private fun receipt(id: String = "visual:info") = PacketVisualClickReceipt().also { it.expect(7, target, id, 1000) }

    @Test fun exactTargetAndContainingSourceAreAcceptedOnlyOnce() {
        for (source in listOf(target, IntRect(50, 150, 250, 350), IntRect(140, 240, 160, 260))) {
            val receipt = receipt()
            assertTrue(receipt.consume(7, source, 1155))
            assertFalse(receipt.consume(7, source, 1156))
            assertEquals(PacketVisualReceiptCheck.Ready, receipt.verify(PacketPage(), 1200))
        }
    }

    @Test fun wrongWindowStaleFutureAndNonTargetSourceAreRejected() {
        assertFalse(receipt().consume(8, target, 1155))
        assertFalse(receipt().consume(7, target, 1601))
        assertFalse(receipt().consume(7, target, 999))
        assertFalse(receipt().consume(7, IntRect(201, 200, 301, 300), 1155))
        assertFalse(receipt().consume(7, IntRect(100, 200, 149, 249), 1155))
        assertTrue(receipt().consume(7, target, 1600))
    }

    @Test fun rejectedReceiptCannotLaterSwallowAnotherClick() {
        val receipt = receipt()
        assertFalse(receipt.consume(8, null, 1155))
        assertFalse(receipt.consume(7, null, 1156))
    }

    @Test fun nullOrEmptyInfoSourceWaitsForVerifiedGroupDetails() {
        for (source in listOf(null, IntRect(0, 0, 0, 0), IntRect(10, 20, 10, 30))) {
            val receipt = receipt()
            assertTrue(receipt.consume(7, source, 1155))
            assertFalse(receipt.consume(7, source, 1156))
            assertEquals(PacketVisualReceiptCheck.Wait, receipt.verify(PacketPage(groupChat = true), 1200))
            assertEquals(PacketVisualReceiptCheck.Wait, receipt.verify(PacketPage(packetPanel = true), 1201))
            assertEquals(PacketVisualReceiptCheck.Ready, receipt.verify(PacketPage(verifiedGroupDetails = true), 1300))
            assertEquals(PacketVisualReceiptCheck.Ready, receipt.verify(PacketPage(), 1400))
        }
    }

    @Test fun cardWithoutSourceRequiresPacketPanelBeforeAnotherOperation() {
        val receipt = receipt("visual:card:100,200,200,300")
        assertTrue(receipt.consume(7, null, 1155))
        assertEquals(PacketVisualReceiptCheck.Wait, receipt.verify(PacketPage(verifiedGroupDetails = true), 1200))
        assertEquals(PacketVisualReceiptCheck.Wait, receipt.verify(PacketPage(chatName = "群聊", cards = listOf("card")), 1250))
        assertEquals(PacketVisualReceiptCheck.Ready, receipt.verify(PacketPage(packetPanel = true), 1300))
    }

    @Test fun openWithoutSourceRequiresResultOnPacketPanel() {
        val receipt = receipt("visual:open")
        assertTrue(receipt.consume(7, null, 1155))
        assertEquals(PacketVisualReceiptCheck.Wait, receipt.verify(PacketPage(packetPanel = true, openButton = "visual:open"), 1200))
        assertEquals(PacketVisualReceiptCheck.Wait, receipt.verify(PacketPage(result = "已领取"), 1250))
        assertEquals(PacketVisualReceiptCheck.Ready, receipt.verify(PacketPage(packetPanel = true, result = "已领取"), 1300))
    }

    @Test fun pendingTimesOutAndNeverBecomesReadyWithoutClear() {
        val receipt = receipt()
        assertTrue(receipt.consume(7, null, 1155))
        assertEquals(PacketVisualReceiptCheck.Wait, receipt.verify(PacketPage(), 3654))
        assertEquals(PacketVisualReceiptCheck.Stop, receipt.verify(PacketPage(), 3655))
        assertEquals(PacketVisualReceiptCheck.Stop, receipt.verify(PacketPage(verifiedGroupDetails = true), 3656))
        receipt.clear()
        assertEquals(PacketVisualReceiptCheck.Ready, receipt.verify(PacketPage(), 4000))
    }
    @Test fun openResultCanArriveAfterThreeSeconds() {
        val receipt = receipt("visual:open")
        assertTrue(receipt.consume(7, null, 1155))
        assertEquals(PacketVisualReceiptCheck.Wait, receipt.verify(PacketPage(packetPanel = true), 4000))
        assertEquals(PacketVisualReceiptCheck.Ready, receipt.verify(PacketPage(packetPanel = true, result = "已领取"), 4155))
    }

    @Test fun openResultConfirmationStillStopsAfterFiveSeconds() {
        val receipt = receipt("visual:open")
        assertTrue(receipt.consume(7, null, 1155))
        assertEquals(PacketVisualReceiptCheck.Wait, receipt.verify(PacketPage(), 6154))
        assertEquals(PacketVisualReceiptCheck.Stop, receipt.verify(PacketPage(packetPanel = true, result = "已领取"), 6155))
    }

    @Test fun clearRemovesBothExpectedAndPendingReceipts() {
        val receipt = receipt()
        receipt.clear()
        assertFalse(receipt.consume(7, null, 1155))
        receipt.expect(7, target, "visual:info", 2000)
        assertTrue(receipt.consume(7, null, 2100))
        receipt.clear()
        assertEquals(PacketVisualReceiptCheck.Ready, receipt.verify(PacketPage(), 2150))
        assertFalse(receipt.consume(7, null, 2200))
    }

    @Test fun unknownActionAndEmptyTargetCannotAcceptAnonymousClicks() {
        assertFalse(receipt("other").consume(7, null, 1155))
        val receipt = PacketVisualClickReceipt()
        receipt.expect(7, IntRect(0, 0, 0, 0), "visual:info", 1000)
        assertFalse(receipt.consume(7, null, 1155))
        assertEquals(PacketVisualReceiptCheck.Ready, receipt.verify(PacketPage(), 1155))
    }
}
