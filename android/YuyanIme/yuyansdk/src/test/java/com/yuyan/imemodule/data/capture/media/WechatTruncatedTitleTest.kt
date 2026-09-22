package com.yuyan.imemodule.data.capture.media

import org.junit.Assert.*
import org.junit.Test

class WechatTruncatedTitleTest {
    private val pixels="a".repeat(64)
    @Test fun visibleTruncatedNameIsReadableButNeverFullyConfirmed() {
        val tracker=WechatTitleStabilizer()
        val first=tracker.observe("测试…店5337(135)",pixels,1000)
        assertEquals("测试…店5337（名称被截断）",first.displayName)
        assertEquals("truncated",first.status)
        assertTrue(first.externalKey.startsWith("screenshot-v2:truncated:"))
        assertTrue(first.confidence<.8)
        val next=tracker.observe("测试…店5337(136)",pixels,1800)
        assertEquals(first.externalKey,next.externalKey)
        assertEquals("truncated",next.status)
    }
    @Test fun sameVisibleNameNeverRecoversAGlobalIdentityAcrossNavigation() {
        val store=MemoryConversationIdentityStore()
        val tracker=WechatTitleStabilizer(store)
        val first=tracker.observe("测试…店",pixels,1000)
        tracker.reset()
        assertNotEquals(first.externalKey,tracker.observe("测试…店",pixels,1800).externalKey)
        assertNotEquals(first.externalKey,WechatTitleStabilizer(store).observe("测试…店",pixels,1800).externalKey)
    }
    @Test fun differentPixelsOrInterveningContactCannotReuseTruncatedSession() {
        val tracker=WechatTitleStabilizer()
        val first=tracker.observe("测试…店",pixels,1000)
        val second=tracker.observe("测试…店","b".repeat(64),1800)
        assertNotEquals(first.externalKey,second.externalKey)
        tracker.observe("完整名称",pixels,2600)
        assertNotEquals(second.externalKey,tracker.observe("测试…店","b".repeat(64),3400).externalKey)
    }
    @Test fun truncatedFirstFrameDoesNotRequestExtraConfirmationScreenshots() = kotlinx.coroutines.runBlocking {
        val identity=WechatTitleStabilizer().observe("测试…店",pixels,1000)
        assertEquals(identity,confirmWechatScreenshotIdentity(identity) { error("截断名称不能靠更多相同帧补全") })
    }
    @Test fun expiredOrUnreadableContextCannotBridgeIdenticalAbbreviations() {
        val tracker=WechatTitleStabilizer()
        val first=tracker.observe("测试…店",pixels,1000)
        val expired=tracker.observe("测试…店",pixels,400000)
        assertNotEquals(first.externalKey,expired.externalKey)
        tracker.observe(null,null,400800)
        assertNotEquals(expired.externalKey,tracker.observe("测试…店",pixels,401600).externalKey)
    }
}
