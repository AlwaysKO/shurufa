package com.yuyan.imemodule.data.redpacket

import org.junit.Assert.*
import org.junit.Test

class GroupRedPacketPolicyTest {
    @Test fun titleMemberCountCannotAuthorizeClaimWithoutGroupDetails() {
        val action = PacketFlow(task(), 0).step(chat(), 10)
        assertNotEquals("联系人也可能叫同学群(3)，群标题不能直接授权点击红包", PacketAction.Click("card"), action)
    }
    private fun notice(text: String = "小明: [微信红包]恭喜发财", group: Boolean? = null) =
        PacketNotice("com.tencent.mm", "notice", "同学群", text, 1_000, group)
    private fun task() = requireNotNull(groupPacketCandidate(notice(), 1_100))
    private fun chat(name: String = "同学群", group: Boolean = true, cards: List<String> = listOf("card")) =
        PacketPage(chatName = name, groupChat = group, cards = cards, chatInfoButton = "info")
    private fun verifiedFlow(): PacketFlow = PacketFlow(task(), 0).also {
        assertEquals(PacketAction.Click("info"), it.step(chat(), 1))
        assertEquals(PacketAction.Back, it.step(PacketPage(verifiedGroupDetails = true), 2))
    }

    @Test fun legacyGroupNotificationStillRequiresPageConfirmation() {
        assertFalse(task().confirmedGroup)
        assertEquals("同学群", task().chatName)
        assertEquals(PacketAction.Stop, PacketFlow(task(), 0).step(chat(group = false), 100))
    }
    @Test fun messagingStylePrivateMessagesAreRejectedEvenWithSenderPrefix() {
        assertNull(groupPacketCandidate(notice(group = false), 1_100))
        assertNotNull(groupPacketCandidate(notice("[微信红包]恭喜发财", true), 1_100))
    }
    @Test fun ordinaryMessagesHiddenSummariesOtherAppsAndStaleNoticesAreRejected() {
        for (n in listOf(notice("记得发红包"), notice("小明: 明天发[微信红包]"),
            notice().copy(title = "微信"), notice().copy(packageName = "other"),
            notice().copy(postedAt = -100_000), notice().copy(postedAt = 9_000))) {
            assertNull(groupPacketCandidate(n, 1_100))
        }
    }
    @Test fun notificationUpdatesForDifferentPacketsRemainDistinct() {
        assertNotEquals(task().id, groupPacketCandidate(notice().copy(postedAt = 1_001), 1_100)!!.id)
        assertEquals(task().id, groupPacketCandidate(notice(), 1_200)!!.id)
    }
    @Test fun latestCardIsClickedOnceThenOnlyThePacketPanelMayOpen() {
        val flow = verifiedFlow()
        assertEquals(PacketAction.Click("new"), flow.step(chat(cards = listOf("old", "new")), 10))
        assertEquals(PacketAction.Wait, flow.step(chat(cards = listOf("old", "new")), 20))
        assertEquals(PacketAction.Wait, flow.step(PacketPage(openButton = "open"), 30))
        assertEquals(PacketAction.Click("open"), flow.step(PacketPage(packetPanel = true, openButton = "open"), 40))
        assertEquals(PacketAction.Wait, flow.step(PacketPage(packetPanel = true, openButton = "open"), 50))
        assertEquals(PacketAction.Finish("已领取"), flow.step(PacketPage(packetPanel = true, result = "已领取"), 60))
    }
    @Test fun matchingExplicitGroupNotificationAllowsTitleWithoutMemberCount() {
        val candidate = groupPacketCandidate(notice(group = true), 1_100)!!
        val flow = PacketFlow(candidate, 0)
        assertEquals(PacketAction.Click("info"), flow.step(chat(group = false), 10))
        assertEquals(PacketAction.Wait, flow.step(PacketPage(), 20))
        assertEquals(PacketAction.Back, flow.step(PacketPage(verifiedGroupDetails = true), 30))
        assertEquals(PacketAction.Click("card"), flow.step(chat(group = false), 40))
    }
    @Test fun wrongChatAndTimeoutStopWithoutClicking() {
        assertEquals(PacketAction.Stop, PacketFlow(task(), 0).step(chat("其他群"), 100))
        assertEquals(PacketAction.Stop, PacketFlow(task(), 0).step(chat(), 12_000))
    }
    @Test fun openingNearDeadlineAllowsBoundedResultLoadingWithoutAnotherClick() {
        val flow = verifiedFlow()
        flow.step(chat(), 6_000)
        assertEquals(PacketAction.Click("open"), flow.step(PacketPage(packetPanel = true, openButton = "open"), 9_000))
        assertTrue(flow.canClick(11_999))
        assertFalse(flow.canClick(12_000))
        assertEquals(PacketAction.Wait, flow.step(PacketPage(packetPanel = true, openButton = "open"), 12_050))
        assertEquals(PacketAction.Finish("已领取"), flow.step(PacketPage(packetPanel = true, result = "已领取"), 12_100))
    }
    @Test fun resultLoadingGraceExpiresAndDoesNotAllowLateOpening() {
        val flow = verifiedFlow()
        flow.step(chat(), 6_000)
        flow.step(PacketPage(packetPanel = true, openButton = "open"), 9_000)
        assertEquals(PacketAction.Wait, flow.step(PacketPage(), 13_999))
        assertEquals(PacketAction.Stop, flow.step(PacketPage(packetPanel = true, result = "已领取"), 14_000))
        val unopened = verifiedFlow()
        unopened.step(chat(), 6_000)
        assertEquals(PacketAction.Stop, unopened.step(PacketPage(packetPanel = true, openButton = "open"), 12_001))
    }
    @Test fun resultWordsInChatNeverCountAsReceipt() {
        assertEquals(PacketAction.Wait, verifiedFlow().step(chat(cards = emptyList()).copy(result = "已领取"), 100))
    }
    @Test fun unavailablePacketsFinishWithoutTryingToOpenAgain() {
        val flow = verifiedFlow()
        flow.step(chat(), 10)
        assertEquals(PacketAction.Finish("已被领完"), flow.step(PacketPage(packetPanel = true, result = "已被领完"), 20))
    }
    @Test fun packetLabelsExcludeReceivedExpiredAndOutgoingCards() {
        assertTrue(isAvailablePacketCard(listOf("恭喜发财", "微信红包"), incoming = true))
        for (status in listOf("已领取", "已被领完", "已过期", "已退还")) {
            assertFalse(isAvailablePacketCard(listOf("微信红包", status), true))
        }
        assertFalse(isAvailablePacketCard(listOf("微信红包"), false))
        assertFalse(isAvailablePacketCard(listOf("今天发微信红包"), true))
    }
    @Test fun recentRequestsAreBoundedAndNotReplayed() {
        val recent = PacketDeduplicator(2)
        assertTrue(recent.accept("one"))
        assertFalse(recent.accept("one"))
        assertTrue(recent.accept("two"))
        assertTrue(recent.accept("three"))
        assertTrue(recent.accept("one"))
    }

    @Test fun privateChatWithGroupLookingNameNeverPassesTheDetailsCheck() {
        val flow = PacketFlow(task(), 0)
        assertEquals(PacketAction.Click("info"), flow.step(chat(), 10))
        assertEquals(PacketAction.Wait, flow.step(PacketPage(verifiedGroupDetails = false), 20))
        assertEquals(PacketAction.Wait, flow.step(chat(), 30))
        assertEquals(PacketAction.Stop, flow.step(chat(), 12_000))
    }
    @Test fun samePositionAndWordingCanBeClaimedAfterPreviousCardWasConsumed() {
        val attempts = VisiblePacketAttempts()
        attempts.observe("群", listOf("same"))
        assertTrue(attempts.accept("群", "same"))
        attempts.observe("群", listOf("same"))
        assertFalse(attempts.accept("群", "same"))
        attempts.observe("群", emptyList()) // 原卡片变成已领取
        attempts.observe("群", listOf("same"))
        assertTrue(attempts.accept("群", "same"))
    }
    @Test fun userClickImmediatelyAfterAutomaticClickIsNeverIgnored() {
        val receipt = PacketClickReceipt()
        receipt.expect(1, "packet", 0)
        assertFalse(receipt.consume(1, "back", 10))
        receipt.expect(1, "packet", 0)
        assertTrue(receipt.consume(1, "packet", 10))
        assertFalse(receipt.consume(1, "packet", 20))
        receipt.expect(1, "packet", 0)
        assertFalse(receipt.consume(2, "packet", 10))
        receipt.expect(1, "packet", 0)
        assertFalse(receipt.consume(1, "packet", 601))
    }
    @Test fun completedCardCanBeReplacedWhileResultPageWasShowing() {
        val attempts = VisiblePacketAttempts()
        assertTrue(attempts.accept("群", "same"))
        attempts.completed("群", "same")
        attempts.observe("群", listOf("same"))
        assertTrue(attempts.accept("群", "same"))
    }
    @Test fun missingReceiptLabelsCannotCauseAnUnboundedRetryLoop() {
        val budget = PacketRetryBudget()
        assertTrue(budget.allow("same", 0))
        assertTrue(budget.allow("same", 1))
        assertTrue(budget.allow("same", 2))
        assertFalse(budget.allow("same", 3))
        assertTrue(budget.allow("different", 3))
        assertTrue(budget.allow("same", 15_000))
    }
}
