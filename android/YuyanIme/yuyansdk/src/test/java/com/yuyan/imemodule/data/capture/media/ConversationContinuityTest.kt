package com.yuyan.imemodule.data.capture.media

import com.yuyan.imemodule.data.capture.model.ChatPlatform
import org.junit.Assert.*
import org.junit.Test

class ConversationContinuityTest {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    @Test fun knownVisualSurvivesRestartAndContinuousAliasesRemainStable() {
        val store = MemoryConversationIdentityStore()
        fun tracker() = ConversationTitleStabilizer(ChatPlatform.WECHAT, "local", "ocr", identityStore = store)
        val first = tracker()
        first.observe("张三", a, 1000)
        val confirmed = first.observe("张三", a, 1800)
        first.observe("张三", b, 2600)
        val nextDay = tracker().observe("张三", b, 1)
        assertEquals(confirmed.externalKey, nextDay.externalKey)
        assertEquals("confirmed", nextDay.status)
    }
    @Test fun unreadablePageUsesOnePendingKeyThenConfirmsInPlace() {
        val tracker = ConversationTitleStabilizer(ChatPlatform.QQ, "local", "accessibility_title")
        val pending = tracker.observe(null, null, 1000)
        assertEquals(pending.externalKey, tracker.observe(null, null, 1800).externalKey)
        assertEquals(pending.externalKey, tracker.observe("张三", a, 2600).externalKey)
        val confirmed = tracker.observe("张三", a, 3400)
        assertEquals(pending.externalKey, confirmed.externalKey)
        assertEquals("confirmed", confirmed.status)
        tracker.reset()
        assertNotEquals(pending.externalKey, tracker.observe(null, null, 4200).externalKey)
    }
    @Test fun pendingRecoveryToRememberedIdentityCarriesExactPreviousKey() {
        val store = MemoryConversationIdentityStore()
        val tracker = ConversationTitleStabilizer(ChatPlatform.QQ, "local", "accessibility_title", identityStore = store)
        tracker.observe("张三", a, 1000); val old = tracker.observe("张三", a, 1800)
        tracker.reset()
        val pending = tracker.observe(null, null, 2600)
        val recovered = tracker.observe("张三", a, 3400)
        assertEquals(old.externalKey, recovered.externalKey)
        assertEquals(pending.externalKey, recovered.previousKey)
    }
    @Test fun matchingNameAloneDoesNotReuseAcrossNavigationOrAccounts() {
        val store = MemoryConversationIdentityStore()
        val t = ConversationTitleStabilizer(ChatPlatform.QQ, "a", "accessibility_title", identityStore = store)
        t.observe("同名", a, 1000); val first=t.observe("同名", a, 1800);t.reset()
        assertNotEquals(first.externalKey,t.observe("同名", b, 2600).externalKey)
        assertNotEquals(first.externalKey,ConversationTitleStabilizer(ChatPlatform.QQ,"b","accessibility_title",identityStore=store).observe("同名",a,1000).externalKey)
    }
}
