package com.yuyan.imemodule.data.capture.media

import com.yuyan.imemodule.data.capture.model.ConversationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WechatScreenshotIdentityTest {
    @Test fun `navigation glyph and status bar are not conversation titles`() {
        assertNull(selectWechatChatTitle(listOf(OcrTextLine("く", 440, 40, 480, 100)), 923, 160))
        assertNull(selectWechatChatTitle(listOf(OcrTextLine("中国移动", 350, 2, 570, 25)), 923, 160))
    }

    @Test fun `single OCR reading is not treated as confirmed confidence`() {
        assertTrue(screenshotConversationIdentity("文件伎输助手", "fallback").confidence < 0.8)
    }

    @Test fun `centered title wins over status and menu text`() {
        val title = selectWechatChatTitle(
            lines = listOf(
                OcrTextLine("17:53", 20, 5, 100, 35),
                OcrTextLine("张由军（鹅掌坦房东）", 220, 52, 700, 115),
                OcrTextLine("···", 835, 55, 900, 110),
            ),
            imageWidth = 923,
            headerHeight = 150,
        )
        assertEquals("张由军（鹅掌坦房东）", title)
    }

    @Test fun `group member count does not change stable key`() {
        val first = screenshotConversationIdentity("周末聚餐群(18)", "fallback-a")
        val second = screenshotConversationIdentity("周末聚餐群（19）", "fallback-b")
        assertEquals("周末聚餐群", first.displayName)
        assertEquals(first.externalKey, second.externalKey)
        assertEquals(ConversationType.GROUP, first.conversationType)
    }

    @Test fun `ocr digit after group suffix and full width punctuation do not split conversation`() {
        val noisy = screenshotConversationIdentity("一起加油！噢力给！(6)8", "fallback-a")
        val clean = screenshotConversationIdentity("一起加油!噢力给!", "fallback-b")

        assertEquals("一起加油！噢力给！", noisy.displayName)
        assertEquals(clean.externalKey, noisy.externalKey)
        assertEquals(ConversationType.GROUP, noisy.conversationType)
    }

    @Test fun `ocr letter after group member count is removed`() {
        val noisy = screenshotConversationIdentity("一路江湖(210)A", "fallback-a")
        val clean = screenshotConversationIdentity("一路江湖(210)", "fallback-b")

        assertEquals("一路江湖", noisy.displayName)
        assertEquals(clean.externalKey, noisy.externalKey)
        assertEquals(ConversationType.GROUP, noisy.conversationType)
    }

    @Test fun `different chat names produce different keys and visual fallback stays isolated`() {
        assertNotEquals(
            screenshotConversationIdentity("张三", "same").externalKey,
            screenshotConversationIdentity("李四", "same").externalKey,
        )
        assertNotEquals(
            screenshotConversationIdentity(null, "header-a").externalKey,
            screenshotConversationIdentity(null, "header-b").externalKey,
        )
    }
}
