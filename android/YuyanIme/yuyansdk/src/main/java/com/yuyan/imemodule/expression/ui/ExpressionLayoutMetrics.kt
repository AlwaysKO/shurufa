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
    val compactPanelHeightPx: Int,
    val minimumCompactPanelHeightPx: Int,
    val maximumCompactPanelHeightPx: Int,
    val visibleItemCount: Float,
) {
    companion object {
        fun calculate(
            widthPx: Int,
            density: Float,
            landscape: Boolean,
            availableHeightPx: Int = Int.MAX_VALUE,
            reservedKeyboardHeightPx: Int = 0,
        ): ExpressionLayoutMetrics {
            require(widthPx > 0) { "width must be positive" }
            require(density > 0f) { "density must be positive" }
            require(availableHeightPx >= 0) { "available height must not be negative" }
            require(reservedKeyboardHeightPx >= 0) { "reserved keyboard height must not be negative" }

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
            ).coerceAtLeast(EXPANDED_COLUMNS)
                .div(EXPANDED_COLUMNS)
                .coerceAtMost(px(if (landscape) 120f else 160f))
            val visibleItemCount =
                (widthPx - horizontalPaddingPx + itemGapPx).toFloat() /
                    (itemSizePx + itemGapPx)

            val desiredTabHeightPx = px(if (landscape) 32f else 36f)
            val desiredContentHeightPx = px(if (landscape) 84f else 96f)
            val desiredToolHeightPx = px(if (landscape) 30f else 34f)
            val designedMaximumHeightPx =
                desiredTabHeightPx + desiredContentHeightPx + desiredToolHeightPx
            val designedMinimumHeightPx =
                desiredTabHeightPx + px(MINIMUM_CONTENT_HEIGHT_DP) + desiredToolHeightPx
            val heightBudgetPx = if (availableHeightPx == Int.MAX_VALUE) {
                designedMaximumHeightPx
            } else {
                (availableHeightPx - reservedKeyboardHeightPx).coerceAtLeast(0)
            }
            val maximumCompactHeightPx = minOf(designedMaximumHeightPx, heightBudgetPx)
            val minimumCompactHeightPx = minOf(designedMinimumHeightPx, maximumCompactHeightPx)
            val compactHeightPx = maximumCompactHeightPx

            val desiredFixedRowsHeight = desiredTabHeightPx + desiredToolHeightPx
            val (tabRowHeightPx, toolRowHeightPx) = if (compactHeightPx >= desiredFixedRowsHeight) {
                desiredTabHeightPx to desiredToolHeightPx
            } else if (compactHeightPx == 0) {
                0 to 0
            } else {
                val tab = (compactHeightPx.toLong() * desiredTabHeightPx / desiredFixedRowsHeight)
                    .toInt()
                tab to compactHeightPx - tab
            }
            val contentHeightPx =
                (compactHeightPx - tabRowHeightPx - toolRowHeightPx).coerceAtLeast(0)

            return ExpressionLayoutMetrics(
                itemSizePx = itemSizePx,
                expandedItemSizePx = expandedItemSizePx,
                itemGapPx = itemGapPx,
                horizontalPaddingPx = horizontalPaddingPx,
                tabRowHeightPx = tabRowHeightPx,
                contentHeightPx = contentHeightPx,
                toolRowHeightPx = toolRowHeightPx,
                actionWidthPx = minOf(
                    px(if (landscape) 74f else 79f),
                    (widthPx - horizontalPaddingPx * 2).coerceAtLeast(0),
                ),
                actionHeightPx = minOf(
                    px(if (landscape) 26f else 28f),
                    tabRowHeightPx,
                ),
                compactPanelHeightPx = compactHeightPx,
                minimumCompactPanelHeightPx = minimumCompactHeightPx,
                maximumCompactPanelHeightPx = maximumCompactHeightPx,
                visibleItemCount = visibleItemCount,
            )
        }

        private const val REFERENCE_WIDTH_PX = 443f
        private const val EXPANDED_COLUMNS = 3
        private const val MINIMUM_CONTENT_HEIGHT_DP = 48f
    }
}
