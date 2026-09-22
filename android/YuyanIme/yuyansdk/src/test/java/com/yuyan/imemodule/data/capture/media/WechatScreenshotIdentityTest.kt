package com.yuyan.imemodule.data.capture.media

import com.yuyan.imemodule.data.capture.model.ConversationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WechatScreenshotIdentityTest {
    @Test fun clippedContactMustNotTurnStatusClockAndIconsIntoAContact() {
        assertNull(selectWechatChatTitle(listOf(
            OcrTextLine("09:49 ◆e",82,52,374,96),
            OcrTextLine("王彦兵",518,168,666,221),
        ),1200,216))
    }
    @Test fun aCenteredContactStartingWithTimeIsNotAStatusBarClock() {
        assertEquals("09:49 同学",selectWechatChatTitle(listOf(OcrTextLine("09:49 同学",420,50,780,100)),1200,143))
    }

    @Test fun windowScopedScreenshotKeepsChatHeaderBeforePageClassification() {
        val window = com.yuyan.imemodule.data.capture.ui.IntRect(0, 90, 1080, 2200)
        val crop = com.yuyan.imemodule.service.capture.emptyTreeScreenshotBounds(window, 1500)
        assertEquals("窗口截图已经有自己的原点，不应再裁掉80px标题", window.top, crop.top)
        assertEquals(1500, crop.bottom)
        val offset = crop.top - window.top
        val title = OcrTextLine("文件传输助手", 350, 50 - offset, 730, 100 - offset)
        assertTrue(isWechatScreenshotChatPage(listOf(title), 1080, 194))
        assertEquals("文件传输助手", selectWechatChatTitle(listOf(title), 1080, 194))
        val listTitle = OcrTextLine("微信", 460, 50 - offset, 620, 100 - offset)
        org.junit.Assert.assertTrue(isWechatScreenshotChatPage(listOf(listTitle), 1080, 194))
    }

    @Test fun knownPageSpellingsKeepOneCategoryAndOnlyDiscoveryIsExcluded() {
        for (name in listOf("微信", "微佳", "朋友圈", "朋友屠", "用友殿", "田友殿", "通讯录", "我")) {
            val line = OcrTextLine(name, 390, 50, 690, 110)
            assertTrue(name, isWechatScreenshotChatPage(listOf(line), 1080, 194))
        }
        for (name in listOf("发现", "发机")) {
            org.junit.Assert.assertFalse(name, isWechatScreenshotChatPage(listOf(OcrTextLine(name, 390, 50, 690, 110)), 1080, 194))
        }
        assertEquals("微信", selectWechatChatTitle(listOf(OcrTextLine("微信", 390, 50, 690, 110)),1080,194))
        // 聊天正文里的“发现”不能覆盖真正的顶部联系人标题。
        assertTrue(isWechatScreenshotChatPage(listOf(OcrTextLine("联系人",390,50,690,110), OcrTextLine("发现",390,145,690,190)),1080,194))
    }

    @Test fun smallVerticalOffsetDoesNotLetLeftWechatBackLabelStealCenteredContactTitle() {
        val lines=listOf(OcrTextLine("微信",110,47,360,102),OcrTextLine("阿明",410,50,670,110))
        assertEquals("阿明",selectWechatChatTitle(lines,1080,194))
    }

    @Test fun fullChatWindowWithoutKeyboardKeepsTopAndBottom() {
        val window = com.yuyan.imemodule.data.capture.ui.IntRect(20, 90, 1100, 2200)
        assertEquals(window, com.yuyan.imemodule.service.capture.emptyTreeScreenshotBounds(window, null))
    }

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
    @Test fun homepageIsNowAllowedButEmptyHeaderIsNotInventedAsContact() {
        val lines=listOf(OcrTextLine("微信",430,50,650,100),OcrTextLine("联系人",250,150,480,200))
        org.junit.Assert.assertTrue(isWechatScreenshotChatPage(lines,1080,220))
        org.junit.Assert.assertFalse(isWechatScreenshotChatPage(emptyList(),1080,220))
        org.junit.Assert.assertTrue(isWechatScreenshotChatPage(listOf(OcrTextLine("工作群",430,50,650,100)),1080,220))
        org.junit.Assert.assertTrue(isWechatScreenshotChatPage(listOf(OcrTextLine("〈",10,50,40,100),OcrTextLine("···",990,50,1050,100)),1080,220))
    }
    @Test fun `detached group count must never become the title`() {
        assertNull(selectWechatChatTitle(listOf(OcrTextLine("(279)", 500, 48, 650, 96)),1200,144))
    }
}
