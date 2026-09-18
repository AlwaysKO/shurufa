package com.yuyan.imemodule.data.capture.media

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking

class WechatTitleStabilizerTest {
    @Test fun `late OCR after reset cannot contaminate another contact`() {
        val tracker = WechatTitleStabilizer()
        val oldVersion = tracker.version()
        tracker.reset()
        tracker.observe("新联系人", "b".repeat(64), 1000)
        val stale = tracker.observe("旧联系人", "a".repeat(64), 1800, oldVersion)
        assertTrue(stale.externalKey.startsWith("screenshot-pending:"))
        val next = tracker.observe("新联系人", "b".repeat(64), 2600)
        assertEquals("新联系人", next.displayName)
    }

    @Test fun `confirmation never assigns first image to a newly opened contact`() = runBlocking {
        val tracker = WechatTitleStabilizer()
        val first = tracker.observe("甲", "a".repeat(64), 1000)
        val result = confirmWechatScreenshotIdentity(first) { tracker.observe("乙", "b".repeat(64), 1800) }
        assertEquals(first, result)
    }

    @Test fun `confirmation retries are bounded and stop once title is stable`() = runBlocking {
        val tracker = WechatTitleStabilizer()
        val first = tracker.observe("王彥兵", "a".repeat(64), 1000)
        var calls = 0
        val result = confirmWechatScreenshotIdentity(first) {
            calls++
            tracker.observe("王彦兵", "a".repeat(64), 1000L + calls * 800)
        }
        assertEquals(2, calls)
        assertEquals("王彦兵", result.displayName)
        assertEquals(first.externalKey, result.externalKey)
        confirmWechatScreenshotIdentity(result) { error("confirmed identity does not require additional screenshot") }
        Unit
    }

    private val pictureA = "a".repeat(64)
    private val pictureB = "b".repeat(64)

    @Test fun `first frame stays pending and second independent frame confirms`() {
        val tracker = WechatTitleStabilizer()
        val first = tracker.observe("王彦兵", pictureA, 1000)
        assertEquals("pending", first.status)
        assertTrue(first.displayName.startsWith("待确认会话"))
        assertTrue(first.confidence < 0.8)
        val confirmed = tracker.observe("王彦兵", pictureA, 1800)
        assertEquals(first.externalKey, confirmed.externalKey)
        assertEquals("confirmed", confirmed.status)
        assertEquals("王彦兵", confirmed.displayName)
    }

    @Test fun `simplified traditional OCR jitter cannot split identical title pixels`() {
        val tracker = WechatTitleStabilizer()
        val first = tracker.observe("王彥兵", pictureA, 1000)
        val second = tracker.observe("王彦兵", pictureA, 1800)
        val third = tracker.observe("王彦兵", pictureA, 2600)
        assertEquals(first.externalKey, second.externalKey)
        assertEquals(first.externalKey, third.externalKey)
        assertEquals("王彦兵", third.displayName)
    }

    @Test fun `one incorrect reading does not rename a confirmed conversation`() {
        val tracker = WechatTitleStabilizer()
        tracker.observe("文件传输助手", pictureA, 1000)
        val correct = tracker.observe("文件传输助手", pictureA, 1800)
        val noisy = tracker.observe("文件伎输助手", pictureA, 2600)
        assertEquals(correct.externalKey, noisy.externalKey)
        assertEquals("文件传输助手", noisy.displayName)
    }

    @Test fun `similar contact with different pixels is never fuzzy merged`() {
        val tracker = WechatTitleStabilizer()
        tracker.observe("王彦兵", pictureA, 1000)
        val first = tracker.observe("王彦兵", pictureA, 1800)
        val second = tracker.observe("王彦斌", pictureB, 2600)
        assertNotEquals(first.externalKey, second.externalKey)
        assertEquals("pending", second.status)
    }

    @Test fun `continuous same title survives pixel changes but navigation resets continuity`() {
        val tracker = WechatTitleStabilizer()
        tracker.observe("聚餐群(18)", pictureA, 1000)
        val first = tracker.observe("聚餐群(18)", pictureA, 1800)
        val next = tracker.observe("聚餐群(19)", pictureB, 2600)
        assertEquals(first.externalKey, next.externalKey)
        tracker.reset()
        assertNotEquals(first.externalKey, tracker.observe("聚餐群(19)", pictureB, 3400).externalKey)
    }

    @Test fun `reprocessing immediately is not a second frame confirmation`() {
        val tracker = WechatTitleStabilizer()
        tracker.observe("联系人", pictureA, 1000)
        assertEquals("pending", tracker.observe("联系人", pictureA, 1010).status)
    }

    @Test fun `expired context does not bridge different title images`() {
        val tracker = WechatTitleStabilizer()
        val first = tracker.observe("联系人", pictureA, 1000)
        val later = tracker.observe("联系人", pictureB, 400000)
        assertNotEquals(first.externalKey, later.externalKey)
    }

    @Test fun `unreadable frames stay isolated and never inherit previous contact`() {
        val tracker = WechatTitleStabilizer()
        tracker.observe("联系人", pictureA, 1000)
        val first = tracker.observe(null, null, 1800)
        val next = tracker.observe(null, null, 2600)
        assertEquals("pending", first.status)
        assertNotEquals(first.externalKey, next.externalKey)
        assertTrue(first.externalKey.startsWith("screenshot-pending:"))
    }

    @Test fun `identity is independent of the first OCR spelling and survives tracker restart`() {
        val first = WechatTitleStabilizer().observe("王彦兵", pictureA, 1000)
        val second = WechatTitleStabilizer().observe("王彥兵", pictureA, 1000)
        assertEquals(first.externalKey, second.externalKey)
        assertTrue(first.externalKey.startsWith("screenshot-v2:"))
    }
}
