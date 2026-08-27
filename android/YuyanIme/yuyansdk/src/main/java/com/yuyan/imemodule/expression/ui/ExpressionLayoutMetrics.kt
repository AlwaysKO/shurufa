package com.yuyan.imemodule.expression.ui

import kotlin.math.roundToInt

data class ExpressionLayoutMetrics(
    val itemSizePx: Int,
    val expandedItemSizePx: Int,
    val itemGapPx: Int,
    val horizontalPaddingPx: Int,
    val tabRowHeightPx: Int,
    val contentHeightPx: Int,
    val toolRowHeightPx: Int,
    val actionWidthPx: Int,
    val actionHeightPx: Int,
    val visibleItemCount: Float,
) {
    companion object {
        fun calculate(widthPx: Int, density: Float, landscape: Boolean): ExpressionLayoutMetrics {
            require(widthPx > 0) { "width must be positive" }
            require(density > 0f) { "density must be positive" }

            fun ratioDp(referencePx: Float): Float =
                widthPx * referencePx / REFERENCE_WIDTH_PX / density
            fun px(dp: Float): Int = (dp * density).roundToInt()

            val itemDp = ratioDp(93f).coerceIn(
                if (landscape) 60f else 68f,
                if (landscape) 76f else 84f,
            )
            val gapDp = ratioDp(8f).coerceIn(5f, 8f)
            val paddingDp = ratioDp(21f).coerceIn(12f, 20f)
            val itemSizePx = px(itemDp)
            val itemGapPx = px(gapDp)
            val horizontalPaddingPx = px(paddingDp)
            val expandedItemSizePx = (
                widthPx - horizontalPaddingPx * 2 - itemGapPx * (EXPANDED_COLUMNS - 1)
            ).coerceAtLeast(EXPANDED_COLUMNS) / EXPANDED_COLUMNS
            val visibleItemCount =
                (widthPx - horizontalPaddingPx + itemGapPx).toFloat() /
                    (itemSizePx + itemGapPx)

            return ExpressionLayoutMetrics(
                itemSizePx = itemSizePx,
                expandedItemSizePx = expandedItemSizePx,
                itemGapPx = itemGapPx,
                horizontalPaddingPx = horizontalPaddingPx,
                tabRowHeightPx = px(if (landscape) 32f else 36f),
                contentHeightPx = px(if (landscape) 84f else 96f),
                toolRowHeightPx = px(if (landscape) 30f else 34f),
                actionWidthPx = px(if (landscape) 74f else 79f),
                actionHeightPx = px(if (landscape) 26f else 28f),
                visibleItemCount = visibleItemCount,
            )
        }

        private const val REFERENCE_WIDTH_PX = 443f
        private const val EXPANDED_COLUMNS = 3
    }
}
