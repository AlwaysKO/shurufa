package com.yuyan.imemodule.data.redpacket

import com.yuyan.imemodule.data.capture.ui.IntRect
import org.junit.Assert.*
import org.junit.Test

class PacketUniqueChatToolbarTest {
    private val title = PacketVisualLine("测试群2)", IntRect(310, 100, 690, 160))
    private val clock = PacketVisualLine("12:30", IntRect(150, 40, 330, 80))
    private val menu = IntRect(900, 112, 955, 143)
    private fun frame(lines: List<PacketVisualLine>, menus: List<IntRect> = listOf(menu)) =
        PacketVisualFrame(1000, 2200, lines, menuDots = menus)

    @Test fun singleSelectedTitlePairedToSingleMenuIsUniqueWithoutGroupNameInference() {
        val page = parsePacketVisualFrame(frame(listOf(title))).page
        assertEquals(title.text, page.chatName)
        assertFalse(page.groupChat); assertTrue(page.uniqueChatToolbar)
        assertFalse(page.verifiedGroupDetails)
    }
    @Test fun twoTopCandidatesWithOnlyOneMenuAlignmentRemainUnique() {
        val result = parsePacketVisualFrame(frame(listOf(title, clock)))
        assertEquals(title.text, result.page.chatName)
        assertTrue(result.page.uniqueChatToolbar)
        assertEquals(setOf("visual:info"), result.targets.keys)
    }
    @Test fun twoMenuAlignedTitlesAreAmbiguousEvenWhenParserSelectsOne() {
        val other = PacketVisualLine("其他标题", IntRect(150, 100, 390, 160))
        assertFalse(parsePacketVisualFrame(frame(listOf(title, other))).page.uniqueChatToolbar)
        assertFalse(parsePacketVisualFrame(frame(listOf(title, title))).page.uniqueChatToolbar)
    }
    @Test fun twoMenuCandidatesOrMissingMenuCannotBeUnique() {
        assertFalse(parsePacketVisualFrame(frame(listOf(title), listOf(menu, menu.copy(left = 880, right = 935)))).page.uniqueChatToolbar)
        assertFalse(parsePacketVisualFrame(frame(listOf(title), emptyList())).page.uniqueChatToolbar)
    }
    @Test fun selectedGroupTitleMustBeTheActuallyMenuPairedTitle() {
        val selected = PacketVisualLine("目标群(2)", IntRect(310, 40, 690, 80))
        val paired = PacketVisualLine("其他标题", IntRect(150, 100, 390, 160))
        val page = parsePacketVisualFrame(frame(listOf(selected, paired))).page
        assertEquals("目标群", page.chatName)
        assertFalse(page.uniqueChatToolbar)
    }
    @Test fun legacyPageDefaultsAndGroupDetailsNeverGrantToolbarEligibility() {
        assertFalse(PacketPage().uniqueChatToolbar)
        val details = frame(listOf(PacketVisualLine("聊天信息(2)", IntRect(280, 100, 720, 160)),
            PacketVisualLine("群聊名称", IntRect(40, 630, 230, 680)),
            PacketVisualLine("群公告", IntRect(40, 770, 200, 820))), emptyList())
        assertFalse(parsePacketVisualFrame(details).page.uniqueChatToolbar)
    }
}
