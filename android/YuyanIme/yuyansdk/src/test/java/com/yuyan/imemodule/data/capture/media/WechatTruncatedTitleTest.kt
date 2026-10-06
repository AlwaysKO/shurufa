package com.yuyan.imemodule.data.capture.media

import org.junit.Assert.*
import org.junit.Test

class WechatTruncatedTitleTest {
    private val pixels="a".repeat(64)
    @Test fun twoOrMoreOcrDotsAreTruncationWhileASingleDotIsLiteral() {
        for (title in listOf("下单找岚岚3(淘..岚岚)","订单..","订单...","订单....")) {
            val tracker=WechatTitleStabilizer()
            val first=tracker.observe(title,pixels,1000)
            val next=tracker.observe(title,pixels,1800)
            assertEquals(title,"truncated",first.status)
            assertEquals(title,"truncated",next.status)
            assertEquals(first.externalKey,next.externalKey)
            assertFalse(next.displayName.contains(".."))
            assertTrue(next.displayName.contains("…"))
        }
        val full=WechatTitleStabilizer()
        full.observe("订单v1.2",pixels,1000)
        assertEquals("confirmed",full.observe("订单v1.2",pixels,1800).status)
    }
    @Test fun visibleTruncatedNameIsReadableButNeverFullyConfirmed() {
        val tracker=WechatTitleStabilizer()
        val first=tracker.observe("测试…店5337(135)",pixels,1000)
        assertEquals("测试…店5337",first.displayName)
        assertEquals("truncated",first.status)
        assertTrue(first.externalKey.startsWith("screenshot-v2:truncated:"))
        assertTrue(first.confidence<.8)
        val next=tracker.observe("测试…店5337(136)",pixels,1800)
        assertEquals(first.externalKey,next.externalKey)
        assertEquals("truncated",next.status)
    }
    @Test fun identicalVisiblePixelsMustNotMergeDifferentGroupsAcrossNavigationOrRestart() {
        val store=MemoryConversationIdentityStore()
        val tracker=WechatTitleStabilizer(store)
        // 两个真实群的隐藏后缀可能不同，但可见标题像素完全相同。
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

    @Test fun ocrVariantWithIdenticalPixelsRetainsTruncatedIdentityWithoutConfirmation() {
        val store=MemoryConversationIdentityStore()
        val tracker=WechatTitleStabilizer(store)
        val first=tracker.observe("下单找岚岚3(淘…岗岚)",pixels,1000)
        val next=tracker.observe("下单找岚岚3(淘..岚岚)",pixels,1800)
        assertEquals(first.externalKey,next.externalKey)
        assertEquals("下单找岚岚3(淘…岚岚)",next.displayName)
        assertEquals("truncated",next.status);assertEquals(.55,next.confidence,0.0)
        assertTrue(next.externalKey.matches(Regex("screenshot-v2:truncated:[a-f0-9-]{36}")))
    }
    @Test fun exactTruncatedEvidenceIsIsolatedByPlatformAccountAndSource() {
        val store=MemoryConversationIdentityStore()
        val keys=com.yuyan.imemodule.data.capture.model.ChatPlatform.entries.flatMap { platform ->
            listOf("first","second").flatMap { account -> listOf("ocr","accessibility").map { source ->
                ConversationTitleStabilizer(platform,account,source,legacyWechat=true,identityStore=store)
                    .observe("很长…群名",pixels,1000).externalKey
            } }
        }
        assertEquals(12,keys.toSet().size)
    }
    @Test fun invalidOrMissingPixelsNeverPersistNameBasedIdentity() {
        val store=MemoryConversationIdentityStore()
        for (invalid in listOf(null,"bad","A".repeat(64))) {
            val first=WechatTitleStabilizer(store).observe("同样…简称",invalid,1000)
            assertNotEquals(first.externalKey,WechatTitleStabilizer(store).observe("同样…简称",invalid,1800).externalKey)
        }
    }
    @Test fun truncatedReadingDoesNotBorrowOrOverwriteConfirmedFullIdentity() {
        val store=MemoryConversationIdentityStore()
        val full=StoredConversationIdentity("screenshot-v2:$pixels","完整群名","group")
        val scope="wechat|wechat-empty-tree|on_device_title_ocr"
        store.save(scope,pixels,full)
        val first=WechatTitleStabilizer(store).observe("完整…群名",pixels,1000)
        assertNotEquals(full.key,first.externalKey)
        assertEquals(full,store.find(scope,pixels))
        assertNotEquals(first.externalKey,WechatTitleStabilizer(store).observe("完整…群名",pixels,1800).externalKey)
    }
    @Test fun cachedTruncatedPixelsCannotBridgeNavigationOrOverwriteContinuousSource() {
        val store=MemoryConversationIdentityStore()
        val tracker=WechatTitleStabilizer(store)
        val first=tracker.observe("订单…群名",pixels,1000)
        val scope="wechat|wechat-empty-tree|on_device_title_ocr|truncated"
        store.save(scope,pixels,StoredConversationIdentity("screenshot-v2:truncated:11111111-1111-1111-1111-111111111111","其他…读法","unknown"))
        val next=tracker.observe("订单…群名",pixels,1800)
        assertEquals(first.externalKey,next.externalKey)
        tracker.reset()
        val afterNavigation=tracker.observe("订单…群名",pixels,2600)
        assertNotEquals(first.externalKey,afterNavigation.externalKey)
        assertNotEquals(store.find(scope,pixels)?.key,afterNavigation.externalKey)
    }
}
