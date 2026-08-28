package com.yuyan.imemodule.keyboard

import kotlin.math.max

/** 合并可能重叠的系统遮挡；底部导航本身由键盘 holder 计入 reserved。 */
internal data class ExpressionSystemObstructions(
    val topPx: Int,
    val bottomExtraPx: Int,
) {
    fun stableViewportHeight(realBoundsHeightPx: Int): Int =
        (realBoundsHeightPx - topPx - bottomExtraPx).coerceAtLeast(0)
}

internal fun mergeExpressionSystemObstructions(
    statusBarTopPx: Int,
    cutoutTopPx: Int,
    navigationBottomPx: Int,
    cutoutBottomPx: Int,
    nonNavigationBottomPx: Int,
): ExpressionSystemObstructions {
    val statusTop = statusBarTopPx.coerceAtLeast(0)
    val cutoutTop = cutoutTopPx.coerceAtLeast(0)
    val navigationBottom = navigationBottomPx.coerceAtLeast(0)
    val cutoutBottomBeyondNavigation =
        (cutoutBottomPx.coerceAtLeast(0) - navigationBottom).coerceAtLeast(0)
    return ExpressionSystemObstructions(
        topPx = max(statusTop, cutoutTop),
        bottomExtraPx = max(
            cutoutBottomBeyondNavigation,
            nonNavigationBottomPx.coerceAtLeast(0),
        ),
    )
}
