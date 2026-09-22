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
        assertEquals(1, calls)
        assertEquals("王彦兵", result.displayName)
        assertEquals(first.externalKey, result.externalKey)
        confirmWechatScreenshotIdentity(result) { error("confirmed identity does not require additional screenshot") }
        Unit
    }

    @Test fun fixedPagesUseCanonicalIdentityWithoutWaitingForAnotherScreenshot() {
        for ((names, expected) in listOf(listOf("朋友圈", "朋友屠", "用友殿", "田友殿") to "朋友圈", listOf("微信", "微佳", "微信(12)") to "微信")) {
            val identities = names.mapIndexed { index, name -> WechatTitleStabilizer().observe(name, index.toString().repeat(64), 1000) }
            assertEquals(1, identities.map { it.externalKey }.distinct().size)
            identities.forEach { assertEquals(expected, it.displayName); assertEquals("confirmed", it.status) }
        }
    }
    @Test fun typingPunctuationNeverCreatesContactAndKeepsCurrentIdentity() {
        val tracker = WechatTitleStabilizer()
        tracker.observe("联系人", pictureA, 1000)
        val known = tracker.observe("联系人", pictureA, 1800)
        for (text in listOf("对方正在输入…", "对方 正在输入：", "对方正在输入中...", "对方正在输入•••")) {
            val status = tracker.observe(text, pictureB, 2600)
            assertEquals(text, known.externalKey, status.externalKey); assertEquals("联系人", status.displayName)
        }
        tracker.reset()
        assertEquals("pending",tracker.observe("对方正在输入：",pictureB,3400).status)
        assertTrue(tracker.observe("对方正在输入：",pictureB,4200).displayName.startsWith("待确认"))
    }
    @Test fun exactSamePixelsAcrossIndependentFramesCanConfirmDespiteOneOcrCharacterJitter() {
        val tracker = WechatTitleStabilizer()
        tracker.observe("王彥兵", pictureA, 1000)
        assertEquals("confirmed",tracker.observe("王彦兵",pictureA,1800).status)
        // 不相似的文字仍需重新确认，即使错误地给了相同视觉指纹。
        assertEquals("pending",WechatTitleStabilizer().let { it.observe("甲甲甲",pictureA,1000);it.observe("乙乙乙",pictureA,1800) }.status)
    }

    @Test fun fixedPageVisualEvidenceSurvivesNewOcrSpellingWithoutFuzzyMatchingOtherPixels() {
        val store=MemoryConversationIdentityStore()
        val first=WechatTitleStabilizer(store).observe("朋友圈",pictureA,1000)
        val later=WechatTitleStabilizer(store).observe("朋反圏",pictureA,1800)
        assertEquals("朋友圈",later.displayName);assertEquals(first.externalKey,later.externalKey)
        assertEquals("pending",WechatTitleStabilizer(store).observe("朋反圏",pictureB,1800).status)
    }
    @Test fun pendingFirstPictureIsConfirmedInPlaceWhenSamePixelsResolveToFixedPage() {
        val tracker=WechatTitleStabilizer()
        val first=tracker.observe("朋反圈",pictureA,1000)
        val fixed=tracker.observe("朋友圈",pictureA,1800)
        assertEquals(first.externalKey,fixed.externalKey);assertEquals("confirmed",fixed.status);assertEquals("朋友圈",fixed.displayName)
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

    @Test fun `count excluded nickname evidence survives navigation while temporary evidence resets`() {
        val tracker = WechatTitleStabilizer()
        tracker.observe("聚餐群(18)", pictureA, 1000)
        val first = tracker.observe("聚餐群(18)", pictureA, 1800)
        val next = tracker.observe("聚餐群(19)", pictureA, 2600)
        assertEquals(first.externalKey, next.externalKey)
        tracker.reset()
        assertEquals(first.externalKey, tracker.observe("聚餐群(19)", pictureA, 3400).externalKey)
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
        assertEquals(first.externalKey, next.externalKey)
        assertTrue(first.externalKey.startsWith("screenshot-v2:pending:"))
    }

    @Test fun `identity is independent of the first OCR spelling and survives tracker restart`() {
        val first = WechatTitleStabilizer().observe("王彦兵", pictureA, 1000)
        val second = WechatTitleStabilizer().observe("王彥兵", pictureA, 1000)
        assertEquals(first.externalKey, second.externalKey)
        assertTrue(first.externalKey.startsWith("screenshot-v2:"))
    }
    @Test fun `same cleaned display name with different original emoji must not merge`() {
        val tracker = WechatTitleStabilizer()
        tracker.observe("测试群(18)", pictureA, 1000)
        val first = tracker.observe("测试群(18)", pictureA, 1800)
        val otherEmoji = tracker.observe("测试群(18)", pictureB, 2600)
        assertNotEquals(first.externalKey, otherEmoji.externalKey)
        assertEquals("pending", otherEmoji.status)
    }
}
