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
        assertEquals((34f * density).roundToInt(), metrics.toolRowHeightPx)
        assertEquals((79f * density).roundToInt(), metrics.actionWidthPx)
        assertEquals((28f * density).roundToInt(), metrics.actionHeightPx)
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
