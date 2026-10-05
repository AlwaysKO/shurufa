package com.yuyan.imemodule.data.redpacket

import com.yuyan.imemodule.data.capture.ui.IntRect
import org.junit.Assert.*
import org.junit.Test

class PacketVisualPolicyTest {
    private fun line(text: String, left: Int, top: Int, right: Int, bottom: Int) =
        PacketVisualLine(text, IntRect(left, top, right, bottom))
    private val title = line("测试群(12)", 310, 100, 690, 160)
    private val menu = IntRect(900, 112, 955, 143)
    private val card = IntRect(150, 650, 760, 870)
    private val marker = line("微信红包", 180, 820, 350, 850)
    private fun chat() = PacketVisualFrame(1000, 2200, listOf(title, marker), listOf(card), menuDots = listOf(menu))
    private fun details() = PacketVisualFrame(1000, 2200, listOf(
        line("聊天信息（12）", 280, 100, 720, 160),
        line("群聊名称", 40, 630, 230, 680), line("群公告", 40, 770, 200, 820),
    ))
    private fun panel() = PacketVisualFrame(1000, 2200, listOf(
        line("小明发了一个红包", 290, 650, 710, 710), line("开", 460, 1140, 540, 1230),
    ), redRegions = listOf(IntRect(120, 420, 880, 1510)))

    @Test fun groupTitleProvidesOnlyHintAndActualCardAndMenuTargets() {
        val result = parsePacketVisualFrame(chat())
        assertEquals("测试群", result.page.chatName)
        assertTrue(result.page.groupChat)
        assertFalse(result.page.verifiedGroupDetails)
        assertEquals(menu, result.targets[result.page.chatInfoButton])
        assertEquals(card, result.targets[result.page.cards.single()])
        assertEquals(PacketAction.Click(result.page.chatInfoButton!!),
            PacketFlow(PacketCandidate("id", "测试群", false), 0).step(result.page, 1))
    }

    @Test fun coordinatesWorkAtDifferentScreenResolutionsAndFontSizes() {
        for (scale in listOf(.72, 1.2, 1.44)) {
            fun IntRect.scaled() = IntRect((left * scale).toInt(), (top * scale).toInt(),
                (right * scale).toInt(), (bottom * scale).toInt())
            val source = chat()
            val result = parsePacketVisualFrame(source.copy(width = (1000 * scale).toInt(), height = (2200 * scale).toInt(),
                lines = source.lines.map { it.copy(bounds = it.bounds.scaled()) },
                orangeRegions = source.orangeRegions.map { it.scaled() }, menuDots = listOf(menu.scaled())))
            assertEquals("测试群", result.page.chatName)
            assertEquals(card.scaled(), result.targets[result.page.cards.single()])
        }
        for (bounds in listOf(IntRect(230, 85, 770, 175), IntRect(370, 112, 630, 146))) {
            assertEquals("测试群", parsePacketVisualFrame(chat().copy(lines = listOf(
                title.copy(text = "测试群 （ 12 ）", bounds = bounds), marker))).page.chatName)
        }
    }

    @Test fun privateChatAndFakeGroupNicknameCannotAuthorizePacketClaim() {
        val privatePage = parsePacketVisualFrame(chat().copy(lines = listOf(title.copy(text = "小明"), marker))).page
        assertFalse(privatePage.groupChat)
        assertEquals(PacketAction.Stop, PacketFlow(PacketCandidate("id", "小明", false), 0).step(privatePage, 1))
        val fakeGroup = parsePacketVisualFrame(chat()).page
        val flow = PacketFlow(PacketCandidate("id", "测试群", false), 0)
        flow.step(fakeGroup, 1)
        assertEquals(PacketAction.Wait, flow.step(privatePage.copy(chatName = null), 2))
    }

    @Test fun missingOrMisalignedMenuNeverBecomesInfoTarget() {
        for (menus in listOf(emptyList(), listOf(menu.copy(top = 750, bottom = 790)),
            listOf(IntRect(10, 112, 60, 143)))) {
            assertNull(parsePacketVisualFrame(chat().copy(menuDots = menus)).page.chatInfoButton)
        }
    }

    @Test fun incomingColorAndExactLabelAreBothRequired() {
        assertTrue(parsePacketVisualFrame(chat().copy(orangeRegions = emptyList())).page.cards.isEmpty())
        assertTrue(parsePacketVisualFrame(chat().copy(lines = listOf(title, marker.copy(text = "有人发微信红包")))).page.cards.isEmpty())
        assertTrue(parsePacketVisualFrame(chat().copy(orangeRegions = listOf(IntRect(400, 650, 950, 870)),
            lines = listOf(title, marker.copy(bounds = IntRect(430, 820, 600, 850))))).page.cards.isEmpty())
        assertTrue(parsePacketVisualFrame(chat().copy(lines = listOf(title, marker.copy(bounds = IntRect(180, 920, 350, 950))))).page.cards.isEmpty())
        // 自己发的自定义封面可能只有左半橙色，不能仅凭色块中心在左半屏判为收到。
        assertTrue(parsePacketVisualFrame(chat().copy(orangeRegions = listOf(IntRect(230, 650, 510, 870)),
            lines = listOf(title, marker.copy(bounds = IntRect(250, 820, 420, 850))))).page.cards.isEmpty())
    }

    @Test fun receivedExpiredAndExclusiveCardsAreNeverAvailable() {
        for (status in listOf("已领取", "已领完", "已被领完", "已过期", "已退还", "专属红包", "小明的专属红包")) {
            val result = parsePacketVisualFrame(chat().copy(lines = chat().lines + line(status, 180, 720, 580, 770)))
            assertTrue(status, result.page.cards.isEmpty())
        }
    }

    @Test fun statusInDifferentMessageDoesNotHideAvailableCardAndLatestCardSortsLast() {
        val second = IntRect(150, 1050, 760, 1270)
        val result = parsePacketVisualFrame(chat().copy(orangeRegions = listOf(second, card), lines = chat().lines + listOf(
            line("已领取", 180, 950, 450, 990), line("微信红包", 180, 1220, 350, 1250))))
        assertEquals(listOf(card, second), result.page.cards.map { result.targets[it] })
    }

    @Test fun settingsRequireTopTitleAndBothActualSettingRows() {
        val page = parsePacketVisualFrame(details()).page
        assertTrue(page.verifiedGroupDetails)
        assertNull(page.chatName)
        assertNull(page.chatInfoButton)
        assertFalse(parsePacketVisualFrame(details().copy(lines = details().lines.dropLast(1))).page.verifiedGroupDetails)
        assertFalse(parsePacketVisualFrame(details().copy(lines = details().lines.map {
            if (it.text.startsWith("聊天信息")) it.copy(bounds = IntRect(200, 400, 800, 460)) else it
        })).page.verifiedGroupDetails)
        assertFalse(parsePacketVisualFrame(chat().copy(lines = chat().lines + details().lines.drop(1))).page.verifiedGroupDetails)
        assertFalse(parsePacketVisualFrame(details().copy(menuDots = listOf(menu))).page.verifiedGroupDetails)
    }

    @Test fun openRequiresLargeRedPanelAndIndependentPacketSemantics() {
        val result = parsePacketVisualFrame(panel())
        val senderPanel = panel().copy(lines = panel().lines.mapIndexed { index, value ->
            if (index == 0) value.copy(text = "测试成员的红包") else value
        })
        assertNotNull(parsePacketVisualFrame(senderPanel).page.openButton)
        assertTrue(result.page.packetPanel)
        assertEquals(IntRect(460, 1140, 540, 1230), result.targets[result.page.openButton])
        assertNull(parsePacketVisualFrame(panel().copy(redRegions = emptyList())).page.openButton)
        assertNull(parsePacketVisualFrame(panel().copy(lines = panel().lines.drop(1))).page.openButton)
        assertNull(parsePacketVisualFrame(panel().copy(redRegions = listOf(IntRect(450, 1130, 550, 1240)))).page.openButton)
        assertNull(parsePacketVisualFrame(chat().copy(lines = chat().lines + line("开", 150, 950, 200, 1000))).page.openButton)
        assertNotNull(parsePacketVisualFrame(panel().copy(lines = panel().lines.map {
            if (it.text == "开") it.copy(text = "開") else it
        })).page.openButton)
    }

    @Test fun modernResultNeedsRedHeaderSenderMoneyAndDepositMessage() {
        val frame = PacketVisualFrame(1000, 2200, listOf(
            line("测试成员的红包 拼", 320, 380, 740, 440),
            line("1.00", 350, 590, 630, 710),
            line("已存入零钱，可直接消费", 250, 750, 760, 800)),
            redRegions = listOf(IntRect(0, 0, 1000, 290)))
        assertEquals("已领取", parsePacketVisualFrame(frame).page.result)
        assertNull(parsePacketVisualFrame(frame.copy(redRegions = emptyList())).page.result)
        assertNull(parsePacketVisualFrame(frame.copy(lines = frame.lines.filterNot { it.text == "1.00" })).page.result)
    }

    @Test fun resultNeedsPacketPageAndNeverComesFromChatBody() {
        for ((words, expected) in listOf("已存入零钱" to "已领取", "手慢了，红包派完了" to "已被领完", "红包已过期" to "已过期")) {
            val status = line(words, 250, 700, 750, 760)
            val result = parsePacketVisualFrame(PacketVisualFrame(1000, 2200, listOf(
                line("红包详情", 350, 100, 650, 160), status)))
            assertTrue(result.page.packetPanel)
            assertEquals(expected, result.page.result)
            assertNull(parsePacketVisualFrame(chat().copy(lines = chat().lines + status)).page.result)
            assertEquals(expected, parsePacketVisualFrame(panel().copy(lines = panel().lines + status)).page.result)
        }
        val misleadingName = chat().copy(lines = listOf(title.copy(text = "红包详情"), marker,
            line("已存入零钱", 200, 1000, 650, 1060)))
        assertFalse(parsePacketVisualFrame(misleadingName).page.packetPanel)
        assertNull(parsePacketVisualFrame(misleadingName).page.result)
    }

    @Test fun unknownScreensAndInvalidDimensionsProduceNoActions() {
        for (frame in listOf(PacketVisualFrame(0, 2200, chat().lines), PacketVisualFrame(1000, 0, chat().lines),
            PacketVisualFrame(1000, 2200, listOf(line("微信红包", 200, 800, 500, 900))))) {
            val match = parsePacketVisualFrame(frame)
            assertEquals(PacketPage(), match.page)
            assertTrue(match.targets.isEmpty())
        }
    }
}
