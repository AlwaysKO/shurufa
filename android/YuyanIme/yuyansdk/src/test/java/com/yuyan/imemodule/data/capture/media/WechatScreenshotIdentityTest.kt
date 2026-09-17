package com.yuyan.imemodule.data.capture.media

import com.yuyan.imemodule.data.capture.model.ConversationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WechatScreenshotIdentityTest {
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
