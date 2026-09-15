package com.yuyan.imemodule.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickKeyboardPanelMetricsTest {
    @Test
    fun `主题页沿用1080参考尺寸的双列卡片比例`() {
        assertEquals(2, QuickKeyboardPanelMetrics.THEME_COLUMN_COUNT)
        assertEquals(474f / 428f, QuickKeyboardPanelMetrics.THEME_CARD_ASPECT_RATIO, 0.0001f)
        assertEquals(36, QuickKeyboardPanelMetrics.CARD_RADIUS_BASE)
        assertEquals(4, QuickKeyboardPanelMetrics.SELECTED_STROKE_BASE)
        assertEquals(36, QuickKeyboardPanelMetrics.THEME_TITLE_TEXT_BASE)
        assertEquals(55, QuickKeyboardPanelMetrics.themeSidePadding(1080))
        assertEquals(24, QuickKeyboardPanelMetrics.themeCardGap(1080))
        assertEquals(474, QuickKeyboardPanelMetrics.themeCardWidth(1080))
        assertEquals(428, QuickKeyboardPanelMetrics.themeCardHeight(1080))
        assertEquals(36, QuickKeyboardPanelMetrics.themeCardRadius(1080))
        assertEquals(4, QuickKeyboardPanelMetrics.themeSelectedStroke(1080))
        assertEquals(36f, QuickKeyboardPanelMetrics.themeTitleTextSize(1080), 0.001f)
    }

    @Test
    fun `输入方式页按五列展示且工具栏保留完整点击高度`() {
        assertEquals(5, QuickKeyboardPanelMetrics.INPUT_COLUMN_COUNT)
        assertTrue(QuickKeyboardPanelMetrics.NAVIGATION_HEIGHT_DP >= 52)
        assertTrue(QuickKeyboardPanelMetrics.MIN_TOUCH_TARGET_DP >= 44)
    }
}
