package com.yuyan.imemodule.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class ExpressionViewportObstructionTest {
    @Test
    fun `状态栏与顶部刘海重叠时只扣较大值`() {
        val merged = mergeExpressionSystemObstructions(
            statusBarTopPx = 72,
            cutoutTopPx = 96,
            navigationBottomPx = 0,
            cutoutBottomPx = 0,
            nonNavigationBottomPx = 0,
        )

        assertEquals(96, merged.topPx)
        assertEquals(0, merged.bottomExtraPx)
        assertEquals(1904, merged.stableViewportHeight(2000))
    }

    @Test
    fun `底部刘海与导航重叠只额外扣超出导航部分`() {
        assertEquals(
            18,
            mergeExpressionSystemObstructions(0, 0, 54, 72, 0).bottomExtraPx,
        )
        assertEquals(
            0,
            mergeExpressionSystemObstructions(0, 0, 72, 54, 0).bottomExtraPx,
        )
        assertEquals(
            24,
            mergeExpressionSystemObstructions(0, 0, 54, 72, 24).bottomExtraPx,
        )
    }
}
