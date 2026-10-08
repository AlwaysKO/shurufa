package com.yuyan.imemodule.data.capture.media

import com.yuyan.imemodule.data.capture.model.ChatPlatform
import org.junit.Assert.*
import org.junit.Test

class ConversationTitleSimplificationTest {
    @Test fun allTitleEntrancesUseSimplifiedNames() {
        val examples = mapOf("康曉林" to "康晓林", "王彥兵" to "王彦兵", "文件傳輸助手" to "文件传输助手")
        for ((raw, expected) in examples) {
            for (platform in ChatPlatform.entries) assertEquals(expected, normalizeConversationTitle(raw, platform))
            assertEquals(expected, screenshotConversationIdentity(raw, "").displayName)
        }
    }

    @Test fun traditionalAndSimplifiedFramesConfirmWithoutLosingOriginalOcr() {
        val tracker = ConversationTitleStabilizer(ChatPlatform.WECHAT, "local", "accessibility_title")
        val first = tracker.observe("王彦兵", "a".repeat(64), 1000)
        val second = tracker.observe("王彥兵", "a".repeat(64), 1800)
        assertEquals("confirmed", second.status)
        assertEquals(first.externalKey, second.externalKey)
        assertEquals("王彦兵", second.displayName)
        assertEquals("王彥兵", second.observedTitle)
    }

    @Test fun truncatedTitleKeepsOriginalOcrAndUnconfirmedIdentity() {
        val result = WechatTitleStabilizer().observe("傳輸項目..(12)", "a".repeat(64), 1000)
        assertEquals("传输项目…", result.displayName)
        assertEquals("傳輸項目..(12)", result.observedTitle)
        assertEquals("truncated", result.status)
    }

    @Test fun phraseExceptionsAndNonChineseCharactersRemainIntact() {
        assertEquals("🙂𠀾A🙂", ConversationTitleSimplifier.simplify("🙂𠁞A🙂"))
        assertEquals("乾隆项目A🙂…", normalizeConversationTitle("乾隆項目A🙂…", ChatPlatform.QQ))
        assertEquals("王彦斌", normalizeConversationTitle("王彥斌", ChatPlatform.QQ))
        assertNotEquals(normalizeConversationTitle("王彥兵", ChatPlatform.QQ), normalizeConversationTitle("王彥斌", ChatPlatform.QQ))
    }
}
