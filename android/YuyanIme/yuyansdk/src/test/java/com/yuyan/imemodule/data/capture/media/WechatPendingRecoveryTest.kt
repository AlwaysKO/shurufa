package com.yuyan.imemodule.data.capture.media

import com.yuyan.imemodule.data.capture.CapturePersistResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WechatPendingRecoveryTest {
    @Test fun `unreadable middle frame does not strand first image confirmation`() = runBlocking {
        val tracker = WechatTitleStabilizer()
        val first = tracker.observe("联系人甲", "a".repeat(64), 1000)
        val saved = mutableListOf<ScreenshotConversationIdentity>()
        var calls = 0
        val result = persistScreenshotBeforeConfirmation(first, { saved += it; CapturePersistResult.INSERTED }) {
            calls++
            if (calls == 1) tracker.observe(null, null, 1800)
            else tracker.observe("联系人甲", "a".repeat(64), 2600)
        }
        assertEquals("confirmed", result.status)
        assertEquals(first.externalKey, result.externalKey)
        assertEquals(2, saved.size)
        assertEquals("pending", saved.first().status)
    }
    @Test fun `quick exit keeps captured first image even if no confirmation follows`() = runBlocking {
        val first = WechatTitleStabilizer().observe("联系人甲", "a".repeat(64), 1000)
        val saved = mutableListOf<ScreenshotConversationIdentity>()
        persistScreenshotBeforeConfirmation(first, { saved += it; CapturePersistResult.INSERTED }) { null }
        assertEquals(listOf(first), saved)
    }
    @Test fun `missing frame never lends previous name to different contact`() = runBlocking {
        val tracker = WechatTitleStabilizer()
        val first = tracker.observe("联系人甲", "a".repeat(64), 1000)
        var calls = 0
        val result = confirmWechatScreenshotIdentity(first) {
            calls++
            if (calls == 1) tracker.observe(null, null, 1800) else tracker.observe("联系人乙", "b".repeat(64), 2600)
        }
        assertEquals(first.externalKey, result.externalKey)
        assertEquals("pending", result.status)
    }
    @Test fun `saved unreadable first image recovers to confirmed continuous contact with original key`() = runBlocking {
        val tracker = WechatTitleStabilizer()
        tracker.observe("联系人甲", "a".repeat(64), 1000)
        val known = tracker.observe("联系人甲", "a".repeat(64), 1800)
        val first = tracker.observe(null, null, 2000)
        val saved = mutableListOf<ScreenshotConversationIdentity>()
        val result = persistScreenshotBeforeConfirmation(first, { saved += it; CapturePersistResult.INSERTED }) {
            tracker.observe("联系人甲", "a".repeat(64), 2600)
        }
        assertEquals("confirmed", result.status)
        assertEquals(known.externalKey, result.externalKey)
        assertEquals(first.externalKey, saved.last().previousKey)
        assertEquals(2, saved.size)
    }
    @Test fun `first image replay does not borrow unrelated intermediate pending fingerprint`() = runBlocking {
        val tracker = WechatTitleStabilizer()
        val first = tracker.observe("联系人甲", "a".repeat(64), 1000)
        val saved = mutableListOf<ScreenshotConversationIdentity>()
        var attempts = 0
        persistScreenshotBeforeConfirmation(first, { saved += it; CapturePersistResult.INSERTED }) {
            attempts++
            if (attempts == 1) tracker.observe(null, null, 1800) else tracker.observe("联系人甲", "a".repeat(64), 2600)
        }
        assertEquals("confirmed", saved.last().status)
        assertEquals(first.externalKey, saved.last().externalKey)
        assertEquals(first.previousKey, saved.last().previousKey)
    }

    @Test fun `after unreadable gap matching name alone cannot reuse different title pixels`() {
        val tracker = WechatTitleStabilizer()
        tracker.observe("同名联系人", "a".repeat(64), 1000)
        val known = tracker.observe("同名联系人", "a".repeat(64), 1800)
        val pending = tracker.observe(null, null, 2000)
        val other = tracker.observe("同名联系人", "b".repeat(64), 2600)
        assertNotEquals(known.externalKey, other.externalKey)
        assertEquals(pending.externalKey, other.externalKey)
        assertEquals("pending", other.status)
    }

}
