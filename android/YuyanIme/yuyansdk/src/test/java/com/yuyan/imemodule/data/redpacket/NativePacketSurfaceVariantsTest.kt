package com.yuyan.imemodule.data.redpacket

import com.yuyan.imemodule.data.capture.ui.IntRect
import org.junit.Assert.*
import org.junit.Test

class NativePacketSurfaceVariantsTest {
    private fun panel(context: String, button: String, ui: NativePacketUi = NativePacketUi.RECEIVE): PacketPage {
        val ticket = NativePacketTicket(1, 100, 8, 0, 1000, 2000, ui)
        val root = NativePacketNode("0", null, IntRect(0, 0, 1000, 2000), windowId = 0)
        val nodes = listOf(root,
            NativePacketNode("0/0", "0", IntRect(200, 400, 800, 480), text = context, windowId = 0),
            NativePacketNode("0/1", "0", IntRect(400, 900, 600, 1100), text = button, clickable = true, windowId = 0))
        return parseNativePacketPage(ticket, NativePacketWindow(8, 0, 0, root.bounds, nodes))!!.page
    }

    @Test fun nativeOpenSupportsObservedTraditionalGlyphAndAccessibilityLabel() {
        for (button in listOf("开", "開", "开红包", "拆红包", "打开红包")) {
            assertEquals(button, "node:open", panel("成员发了一个红包", button).openButton)
            assertNull(panel("成员发了一个红包", button, NativePacketUi.NONE).openButton)
        }
    }

    @Test fun receiveContextRetainsPreviouslySupportedWeChatPhrases() {
        for (context in listOf("成员发了一个红包", "成员发来一个红包", "成员发出的红包")) {
            assertEquals(context, "node:open", panel(context, "开").openButton)
            assertNull(panel(context, "开", NativePacketUi.NONE).openButton)
        }
    }
}
