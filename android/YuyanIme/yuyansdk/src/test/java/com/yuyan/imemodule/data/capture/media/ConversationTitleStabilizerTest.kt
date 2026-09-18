package com.yuyan.imemodule.data.capture.media

import com.yuyan.imemodule.data.capture.CapturePersistResult
import com.yuyan.imemodule.data.capture.model.ChatPlatform
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ConversationTitleStabilizerTest {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    @Test fun allPlatformsShareNameConfirmationWithoutUsingNameAsKey() {
        for (platform in ChatPlatform.entries) {
            val tracker = ConversationTitleStabilizer(platform, "local", "accessibility_title")
            val first = tracker.observe("王彥兵", a, 1000)
            val jitter = tracker.observe("王彦兵", a, 1800)
            val final = tracker.observe("王彦兵", a, 2600)
            assertEquals("pending", first.status)
            assertEquals(first.externalKey, jitter.externalKey)
            assertEquals(first.externalKey, final.externalKey)
            assertEquals("王彦兵", final.displayName)
            assertTrue(first.externalKey.startsWith("capture-v3:"))
        }
    }
    @Test fun accessibilityDoesNotApplyOCRNoiseCorrectionToLiteralNicknames() {
        val tracker = ConversationTitleStabilizer(ChatPlatform.QQ, "local", "accessibility_title")
        tracker.observe("张三（2）", a, 1000)
        assertEquals("张三（2）", tracker.observe("张三（2）", a, 1800).displayName)
    }

    @Test fun truncatedNameRemainsPendingInsteadOfMergingDifferentLongNames() {
        val tracker = ConversationTitleStabilizer(ChatPlatform.DOUYIN, "local", "accessibility_title")
        tracker.observe("很长的群名…", a, 1000)
        assertEquals("pending", tracker.observe("很长的群名…", a, 1800).status)
    }

    @Test fun platformAndAccountEvidenceRemainIsolated() {
        val keys = ChatPlatform.entries.flatMap { platform ->
            listOf("account-a", "account-b").map { account ->
                ConversationTitleStabilizer(platform, account, "accessibility_title").observe("同名", a, 1000).externalKey
            }
        }
        assertEquals(6, keys.toSet().size)
    }
    @Test fun transientTypingDoesNotReplaceAnEstablishedNameOrAddVotes() {
        val tracker = ConversationTitleStabilizer(ChatPlatform.QQ, "local", "accessibility_title")
        val first = tracker.observe("张三", a, 1000)
        assertEquals("pending", tracker.observe("对方正在输入...", b, 1800).status)
        val confirmed = tracker.observe("张三", a, 2600)
        val typing = tracker.observe("对方正在输入…", b, 3400)
        assertEquals(first.externalKey, typing.externalKey)
        assertEquals(confirmed.displayName, typing.displayName)
        tracker.reset()
        assertNotEquals(first.externalKey, tracker.observe("对方正在输入…", b, 4200).externalKey)
    }
    @Test fun memberCountAndSeparateStatusLineAreNotPeerNames() {
        for (platform in ChatPlatform.entries) {
            assertEquals("项目群", normalizeConversationTitle("项目群（12人）", platform))
            assertEquals("张三", normalizeConversationTitle("张三\n在线", platform))
            assertEquals("张三（同事）", normalizeConversationTitle("张三（同事）", platform))
        }
    }
    @Test fun similarContactsWithDifferentPixelsRemainSeparateAndLateResultsAreRejected() {
        val tracker = ConversationTitleStabilizer(ChatPlatform.DOUYIN, "local", "accessibility_title")
        val first = tracker.observe("张三", a, 1000)
        val version = tracker.version()
        tracker.reset()
        val next = tracker.observe("张山", b, 1800)
        val late = tracker.observe("张三", a, 2600, version)
        assertNotEquals(first.externalKey, next.externalKey)
        assertEquals("pending", late.status)
        assertNotEquals(next.externalKey, late.externalKey)
    }
    @Test fun firstScreenshotIsDurableBeforeAnyConfirmationOrNavigation() = runBlocking {
        val first = ConversationTitleStabilizer(ChatPlatform.QQ, "local", "accessibility_title").observe("名字", a, 1000)
        val events = mutableListOf<String>()
        persistScreenshotBeforeConfirmation(first,
            persist = { events += "persist:${it.status}"; CapturePersistResult.INSERTED },
            observeNext = { assertEquals(listOf("persist:pending"), events); events += "navigation"; null },
        )
        assertEquals(listOf("persist:pending", "navigation"), events)
    }
}
