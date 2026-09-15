package com.yuyan.imemodule.expression.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class ExpressionLayoutMetricsTest {
    @Test
    fun `Honor 200 按参考图比例显示约四点三张卡片`() {
        val density = 3.25f

        val metrics = ExpressionLayoutMetrics.calculate(
            widthPx = 1200,
            density = density,
            landscape = false,
        )

        assertEquals(78f, metrics.itemSizePx / density, 1.5f)
        assertEquals(7f, metrics.itemGapPx / density, 1f)
        assertEquals(17f, metrics.horizontalPaddingPx / density, 1f)
        assertTrue(metrics.visibleItemCount in 4.1f..4.4f)
        assertEquals((32f * density).roundToInt(), metrics.tabRowHeightPx)
        assertTrue(kotlin.math.abs(metrics.contentHeightPx - metrics.itemSizePx - 4f * density) <= 1f)
        assertEquals(0, metrics.toolRowHeightPx)
        assertEquals((64f * density).roundToInt(), metrics.actionWidthPx)
        assertEquals(metrics.tabRowHeightPx - (3f * density).roundToInt() * 2, metrics.actionHeightPx)
    }

    @Test
    fun `紧凑面板总高度等于三层之和且横屏主动压缩`() {
        val portrait = ExpressionLayoutMetrics.calculate(1080, 3f, landscape = false)
        val landscape = ExpressionLayoutMetrics.calculate(1920, 3f, landscape = true)

        assertEquals(
            portrait.tabRowHeightPx + portrait.contentHeightPx + portrait.toolRowHeightPx,
            portrait.compactPanelHeightPx,
        )
        assertEquals(
            landscape.tabRowHeightPx + landscape.contentHeightPx + landscape.toolRowHeightPx,
            landscape.compactPanelHeightPx,
        )
        assertTrue(landscape.compactPanelHeightPx <= (120f * 3f).roundToInt())
        assertTrue(portrait.compactPanelHeightPx <= (150f * 3f).roundToInt())
        assertTrue(landscape.compactPanelHeightPx <= (140f * 3f).roundToInt())
    }

    @Test
    fun `横屏Emoji模式完整预算优先提供两个四十四dp可访问层`() {
        val metrics = ExpressionLayoutMetrics.calculate(
            widthPx = 1920,
            density = 3f,
            landscape = true,
            emojiMode = true,
        )

        assertEquals(276, metrics.contentHeightPx)
        assertEquals(372, metrics.compactPanelHeightPx)
        assertEquals(
            metrics.compactPanelHeightPx,
            metrics.tabRowHeightPx + metrics.contentHeightPx + metrics.toolRowHeightPx,
        )
    }

    @Test
    fun `矮屏Emoji模式优先保留四十四dp单层内容`() {
        val metrics = ExpressionLayoutMetrics.calculate(
            widthPx = 1080,
            density = 3f,
            landscape = false,
            emojiMode = true,
            availableHeightPx = 720,
            reservedKeyboardHeightPx = 456,
        )

        assertEquals(168, metrics.contentHeightPx)
        assertEquals(96, metrics.tabRowHeightPx)
        assertEquals(0, metrics.toolRowHeightPx)
        assertEquals(264, metrics.compactPanelHeightPx)
    }

    @Test
    fun `横屏紧凑面板受真实可用高度和键盘保留高度约束`() {
        val metrics = ExpressionLayoutMetrics.calculate(
            widthPx = 1920,
            density = 3f,
            landscape = true,
            availableHeightPx = 1080,
            reservedKeyboardHeightPx = 720,
        )

        assertTrue(metrics.compactPanelHeightPx <= 360)
        assertEquals(
            metrics.compactPanelHeightPx,
            metrics.tabRowHeightPx + metrics.contentHeightPx + metrics.toolRowHeightPx,
        )
        assertTrue(metrics.contentHeightPx >= 0)
    }

    @Test
    fun `矮屏优先保留候选和键盘而继续压缩紧凑面板`() {
        val metrics = ExpressionLayoutMetrics.calculate(
            widthPx = 1080,
            density = 3f,
            landscape = false,
            availableHeightPx = 720,
            reservedKeyboardHeightPx = 600,
        )

        assertEquals(120, metrics.compactPanelHeightPx)
        assertTrue(metrics.tabRowHeightPx >= 0)
        assertTrue(metrics.toolRowHeightPx >= 0)
        assertTrue(metrics.contentHeightPx >= 0)
    }

    @Test
    fun `极端高度严格遵循预算不保留独占工具行`() {
        val metrics = ExpressionLayoutMetrics.calculate(
            widthPx = 1080,
            density = 3f,
            landscape = false,
            availableHeightPx = 610,
            reservedKeyboardHeightPx = 600,
        )

        assertEquals(0, metrics.toolRowHeightPx)
        assertEquals(10, metrics.compactPanelHeightPx)
        assertEquals(10, metrics.tabRowHeightPx)
        assertEquals(0, metrics.contentHeightPx)
    }

    @Test
    fun `竖屏高度充足时使用参考紧凑高度但不超过最大值`() {
        val metrics = ExpressionLayoutMetrics.calculate(
            widthPx = 1080,
            density = 3f,
            landscape = false,
            availableHeightPx = 2400,
            reservedKeyboardHeightPx = 900,
        )

        assertEquals(335, metrics.compactPanelHeightPx)
        assertTrue(metrics.compactPanelHeightPx <= metrics.maximumCompactPanelHeightPx)
        assertTrue(metrics.minimumCompactPanelHeightPx <= metrics.compactPanelHeightPx)
    }

    @Test
    fun `不同宽度和横屏应用尺寸上下限`() {
        val small = ExpressionLayoutMetrics.calculate(720, 3f, landscape = false)
        val normal = ExpressionLayoutMetrics.calculate(1080, 3f, landscape = false)
        val tablet = ExpressionLayoutMetrics.calculate(2560, 2f, landscape = false)
        val landscape = ExpressionLayoutMetrics.calculate(2664, 3.25f, landscape = true)

        assertTrue(small.itemSizePx / 3f >= 68f)
        assertTrue(normal.itemSizePx > small.itemSizePx)
        assertTrue(tablet.itemSizePx / 2f <= 84f)
        assertTrue(landscape.itemSizePx / 3.25f <= 76f)
        assertTrue(tablet.expandedItemSizePx / 2f <= 160f)
        assertTrue(landscape.expandedItemSizePx / 3.25f <= 120f)
        assertTrue(listOf(small, normal, tablet, landscape).all {
            it.itemGapPx > 0 && it.horizontalPaddingPx > 0 && it.expandedItemSizePx > 0
        })
    }
}
