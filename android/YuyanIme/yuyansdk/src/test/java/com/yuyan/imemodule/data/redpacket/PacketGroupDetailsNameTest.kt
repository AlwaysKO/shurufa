package com.yuyan.imemodule.data.redpacket

import com.yuyan.imemodule.data.capture.ui.IntRect
import org.junit.Assert.*
import org.junit.Test

class PacketGroupDetailsNameTest {
    private fun line(text: String, left: Int, top: Int, right: Int, bottom: Int) =
        PacketVisualLine(text, IntRect(left, top, right, bottom))
    private val title = line("聊天信息（12）", 280, 100, 720, 160)
    private val nameLabel = line("群聊名称", 40, 630, 230, 680)
    private val announcement = line("群公告", 40, 770, 200, 820)
    private val nameValue = line("研发2组（项目）", 550, 630, 880, 680)
    private fun frame(values: List<PacketVisualLine> = listOf(nameValue), heading: PacketVisualLine = title) =
        PacketVisualFrame(1000, 2200, listOf(heading, nameLabel, announcement) + values)
    private fun assertAbsent(frame: PacketVisualFrame) {
        val page = parsePacketVisualFrame(frame).page
        assertNull(page.groupDetailsName); assertNull(page.groupDetailsMemberCount)
    }
    @Test fun completeNameAndMemberCountComeOnlyFromActualGroupDetailRows() {
        val match = parsePacketVisualFrame(frame())
        assertTrue(match.page.verifiedGroupDetails)
        assertEquals("研发2组（项目）", match.page.groupDetailsName)
        assertEquals(12, match.page.groupDetailsMemberCount ?: -1)
        assertNull(match.page.chatName); assertTrue(match.targets.isEmpty())
    }
    @Test fun completeDifferentNameIsPreservedWithoutCandidateOrPrefixMatching() {
        val page = parsePacketVisualFrame(frame(listOf(nameValue.copy(text = "另一个群2")))).page
        assertEquals("另一个群2", page.groupDetailsName)
        assertNotEquals("研发2组", page.groupDetailsName)
    }
    @Test fun fullwidthOrSpacedHeadingStillRequiresAtLeastTwoMembers() {
        val page = parsePacketVisualFrame(frame(heading = title.copy(text = "聊天信息 ( 2 )"))).page
        assertEquals(2, page.groupDetailsMemberCount ?: -1)
        assertEquals(nameValue.text, page.groupDetailsName)
        for (text in listOf("聊天信息(0)", "聊天信息(1)", "聊天信息2)", "聊天信息(2", "聊天信息", "聊天信息(100000)"))
            assertAbsent(frame(heading = title.copy(text = text)))
    }
    @Test fun oldTagOnlyVerifiedBooleanDoesNotInventANameOrMemberEvidence() {
        val page = parsePacketVisualFrame(frame(emptyList())).page
        assertTrue(page.verifiedGroupDetails)
        assertNull(page.groupDetailsName); assertNull(page.groupDetailsMemberCount)
    }
    @Test fun truncatedOrMultipleValuesAreNeverTreatedAsFullNames() {
        for (text in listOf("研发2组…", "研发2组...", "研发⋯2组", "", "  ", "群名\n另一行"))
            assertAbsent(frame(listOf(nameValue.copy(text = text))))
        assertAbsent(frame(listOf(nameValue, nameValue.copy(text = "其他群", bounds = IntRect(540, 635, 850, 680)))))
    }
    @Test fun wrongRowLeftSideEdgeClippingOrOutOfBoundsCannotSupplyName() {
        for (box in listOf(IntRect(50, 630, 220, 680), IntRect(550, 710, 880, 760),
            IntRect(550, 630, 990, 680), IntRect(550, 630, 1001, 680),
            IntRect(550, 600, 880, 720), IntRect(550, -1, 880, 30)))
            assertAbsent(frame(listOf(nameValue.copy(bounds = box))))
    }
    @Test fun conflictingLabelsHeadingOrMenuCannotAuthorizeDetailName() {
        assertAbsent(frame().copy(lines = frame().lines + nameLabel.copy(bounds = IntRect(40, 900, 230, 950))))
        assertAbsent(frame().copy(lines = frame().lines + announcement.copy(bounds = IntRect(40, 950, 200, 1000))))
        assertAbsent(frame().copy(lines = frame().lines + title.copy(text = "聊天信息(3)")))
        assertAbsent(frame().copy(menuDots = listOf(IntRect(900, 112, 955, 143))))
        assertAbsent(frame().copy(lines = frame().lines.filter { it !== announcement }))
    }
    @Test fun sameRowExtractionScalesWithActualFrameInsteadOfFixedPixelCoordinates() {
        for (scale in listOf(.48, 1.2)) {
            val original = frame()
            val resized = original.copy(width = (original.width * scale).toInt(), height = (original.height * scale).toInt(),
                lines = original.lines.map { line -> line.copy(bounds = line.bounds.let { box ->
                    IntRect((box.left * scale).toInt(), (box.top * scale).toInt(),
                        (box.right * scale).toInt(), (box.bottom * scale).toInt())
                }) })
            assertEquals(nameValue.text, parsePacketVisualFrame(resized).page.groupDetailsName)
            assertEquals(12, parsePacketVisualFrame(resized).page.groupDetailsMemberCount ?: -1)
        }
    }
}
