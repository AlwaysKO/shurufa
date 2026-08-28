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
        assertEquals((36f * density).roundToInt(), metrics.tabRowHeightPx)
        assertEquals((96f * density).roundToInt(), metrics.contentHeightPx)
        assertEquals((44f * density).roundToInt(), metrics.toolRowHeightPx)
        assertEquals((79f * density).roundToInt(), metrics.actionWidthPx)
        assertEquals((28f * density).roundToInt(), metrics.actionHeightPx)
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
        assertTrue(landscape.compactPanelHeightPx < portrait.compactPanelHeightPx)
        assertTrue(portrait.compactPanelHeightPx <= (176f * 3f).roundToInt())
        assertTrue(landscape.compactPanelHeightPx <= (160f * 3f).roundToInt())
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

        assertEquals(132, metrics.compactPanelHeightPx)
        assertTrue(metrics.tabRowHeightPx >= 0)
        assertTrue(metrics.toolRowHeightPx >= 0)
        assertTrue(metrics.contentHeightPx >= 0)
    }

    @Test
    fun `极端高度仍保护四十四dp工具入口且只允许这一可访问高度溢出`() {
        val metrics = ExpressionLayoutMetrics.calculate(
            widthPx = 1080,
            density = 3f,
            landscape = false,
            availableHeightPx = 610,
            reservedKeyboardHeightPx = 600,
        )

        assertEquals(132, metrics.toolRowHeightPx)
        assertEquals(132, metrics.compactPanelHeightPx)
        assertEquals(0, metrics.tabRowHeightPx)
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

        assertEquals((176f * 3f).roundToInt(), metrics.compactPanelHeightPx)
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
