package com.yuyan.imemodule.data.redpacket

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class GroupRedPacketWindowTest {
    private fun node(text: String? = null, bounds: Rect = Rect(0, 0, 1080, 1920),
        id: String? = null, type: String = "android.widget.TextView", clickable: Boolean = false,
        vararg children: AccessibilityNodeInfo): AccessibilityNodeInfo = AccessibilityNodeInfo.obtain().apply {
        packageName = PACKET_WECHAT
        this.text = text
        className = type
        viewIdResourceName = id
        isClickable = clickable
        isVisibleToUser = true
        isEnabled = true
        setBoundsInScreen(bounds)
        children.forEach { shadowOf(this).addChild(it) }
    }
    private fun root(vararg children: AccessibilityNodeInfo) = node(type = "android.widget.FrameLayout", children = children)
    private fun chatRoot(status: String? = null, outgoing: Boolean = false): AccessibilityNodeInfo {
        val cardBounds = if (outgoing) Rect(550, 600, 1000, 850) else Rect(80, 600, 530, 850)
        return root(
            node("同学群(3)", Rect(160, 60, 700, 130), "com.tencent.mm:id/chatting_title"),
            node(null, Rect(80, 1700, 900, 1800), "com.tencent.mm:id/chatting_content_et", "android.widget.EditText"),
            node("聊天信息", Rect(930, 60, 1050, 130), clickable = true),
            node(bounds = cardBounds, clickable = true, children = arrayOf(
                node("微信红包", cardBounds), node(status ?: "恭喜发财", cardBounds))),
        )
    }
    @Test fun chatPageExposesInfoAndOnlyAvailableIncomingCards() {
        PacketWindow(chatRoot(), "com.tencent.mm.ui.LauncherUI").use {
            assertEquals("同学群", it.page.chatName)
            assertNotNull(it.page.chatInfoButton)
            assertEquals(1, it.page.cards.size)
            assertFalse(it.page.verifiedGroupDetails)
        }
        for (root in listOf(chatRoot("已领取"), chatRoot(outgoing = true))) PacketWindow(root, "").use {
            assertTrue(it.page.cards.isEmpty())
        }
    }
    @Test fun groupSettingsRequireBothLabelsOutsideChat() {
        PacketWindow(root(node("群聊名称"), node("群公告")), "com.tencent.mm.ui.chatting.ChatroomInfoUI").use {
            assertTrue(it.page.verifiedGroupDetails)
        }
        PacketWindow(root(node("聊天信息"), node("消息免打扰")), "").use {
            assertFalse(it.page.verifiedGroupDetails)
        }
    }
    @Test fun openTextWithoutPacketPanelNeverBecomesAction() {
        PacketWindow(root(node("开", clickable = true)), "com.tencent.mm.ui.LauncherUI").use {
            assertNull(it.page.openButton)
        }
        PacketWindow(root(node("开", clickable = true)), "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyNotHookReceiveUI").use {
            assertNotNull(it.page.openButton)
        }
    }
}
