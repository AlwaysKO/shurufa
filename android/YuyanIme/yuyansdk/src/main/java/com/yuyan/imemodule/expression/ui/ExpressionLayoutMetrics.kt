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
    val compactPanelHeightPx: Int,
    val minimumCompactPanelHeightPx: Int,
    val maximumCompactPanelHeightPx: Int,
) {
    /** 保留初版十参数 JVM 构造入口，新高度字段取旧布局三行总高。 */
    constructor(
        itemSizePx: Int,
        expandedItemSizePx: Int,
        itemGapPx: Int,
        horizontalPaddingPx: Int,
        tabRowHeightPx: Int,
        contentHeightPx: Int,
        toolRowHeightPx: Int,
        actionWidthPx: Int,
        actionHeightPx: Int,
        visibleItemCount: Float,
    ) : this(
        itemSizePx,
        expandedItemSizePx,
        itemGapPx,
        horizontalPaddingPx,
        tabRowHeightPx,
        contentHeightPx,
        toolRowHeightPx,
        actionWidthPx,
        actionHeightPx,
        visibleItemCount,
        tabRowHeightPx + contentHeightPx + toolRowHeightPx,
        tabRowHeightPx + contentHeightPx + toolRowHeightPx,
        tabRowHeightPx + contentHeightPx + toolRowHeightPx,
    )

    /** 初版 data class 的十参数 copy 描述符。 */
    fun copy(
        itemSizePx: Int,
        expandedItemSizePx: Int,
        itemGapPx: Int,
        horizontalPaddingPx: Int,
        tabRowHeightPx: Int,
        contentHeightPx: Int,
        toolRowHeightPx: Int,
        actionWidthPx: Int,
        actionHeightPx: Int,
        visibleItemCount: Float,
    ) = ExpressionLayoutMetrics(
        itemSizePx,
        expandedItemSizePx,
        itemGapPx,
        horizontalPaddingPx,
        tabRowHeightPx,
        contentHeightPx,
        toolRowHeightPx,
        actionWidthPx,
        actionHeightPx,
        visibleItemCount,
    )

    companion object {
        /** Kotlin 旧二进制调用默认 copy 参数时使用的静态桥。 */
        @JvmStatic
        @Suppress("UNUSED_PARAMETER")
        fun `copy$default`(
            self: ExpressionLayoutMetrics,
            itemSizePx: Int,
            expandedItemSizePx: Int,
            itemGapPx: Int,
            horizontalPaddingPx: Int,
            tabRowHeightPx: Int,
            contentHeightPx: Int,
            toolRowHeightPx: Int,
            actionWidthPx: Int,
            actionHeightPx: Int,
            visibleItemCount: Float,
            mask: Int,
            marker: Any?,
        ): ExpressionLayoutMetrics = self.copy(
            itemSizePx = if (mask and 0x001 != 0) self.itemSizePx else itemSizePx,
            expandedItemSizePx = if (mask and 0x002 != 0) self.expandedItemSizePx else expandedItemSizePx,
            itemGapPx = if (mask and 0x004 != 0) self.itemGapPx else itemGapPx,
            horizontalPaddingPx = if (mask and 0x008 != 0) self.horizontalPaddingPx else horizontalPaddingPx,
            tabRowHeightPx = if (mask and 0x010 != 0) self.tabRowHeightPx else tabRowHeightPx,
            contentHeightPx = if (mask and 0x020 != 0) self.contentHeightPx else contentHeightPx,
            toolRowHeightPx = if (mask and 0x040 != 0) self.toolRowHeightPx else toolRowHeightPx,
            actionWidthPx = if (mask and 0x080 != 0) self.actionWidthPx else actionWidthPx,
            actionHeightPx = if (mask and 0x100 != 0) self.actionHeightPx else actionHeightPx,
            visibleItemCount = if (mask and 0x200 != 0) self.visibleItemCount else visibleItemCount,
        )

        fun calculate(
            widthPx: Int,
            density: Float,
            landscape: Boolean,
        ): ExpressionLayoutMetrics = calculate(
            widthPx = widthPx,
            density = density,
            landscape = landscape,
            emojiMode = false,
            availableHeightPx = Int.MAX_VALUE,
            reservedKeyboardHeightPx = 0,
        )

        fun calculate(
            widthPx: Int,
            density: Float,
            landscape: Boolean,
            emojiMode: Boolean = false,
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

            val desiredTabHeightPx = px(MINIMUM_TOUCH_TARGET_DP)
            val desiredContentHeightPx = px(
                if (emojiMode) MINIMUM_EMOJI_TWO_LAYER_DP else if (landscape) 84f else 96f,
            )
            // 恢复入口是面板在无结果/关闭态的唯一入口，必须始终保留可访问触摸高度。
            val desiredToolHeightPx = px(MINIMUM_TOUCH_TARGET_DP)
            val designedMaximumHeightPx =
                desiredTabHeightPx + desiredContentHeightPx + desiredToolHeightPx
            val designedMinimumHeightPx =
                desiredTabHeightPx + px(MINIMUM_CONTENT_HEIGHT_DP) + desiredToolHeightPx
            val heightBudgetPx = if (availableHeightPx == Int.MAX_VALUE) {
                designedMaximumHeightPx
            } else {
                (availableHeightPx - reservedKeyboardHeightPx).coerceAtLeast(0)
            }
            // 当整个视口连 44dp 都放不下时，宁可仅让工具入口溢出，也不能把恢复入口压没。
            val maximumCompactHeightPx = minOf(
                designedMaximumHeightPx,
                maxOf(heightBudgetPx, desiredToolHeightPx),
            )
            val minimumCompactHeightPx = minOf(designedMinimumHeightPx, maximumCompactHeightPx)
            val compactHeightPx = maximumCompactHeightPx

            val toolRowHeightPx = desiredToolHeightPx
            val heightAfterToolPx = (compactHeightPx - toolRowHeightPx).coerceAtLeast(0)
            val minimumEmojiContentPx = px(MINIMUM_TOUCH_TARGET_DP)
            val contentHeightPx = if (emojiMode && heightAfterToolPx >= minimumEmojiContentPx) {
                minOf(
                    desiredContentHeightPx,
                    maxOf(minimumEmojiContentPx, heightAfterToolPx - desiredTabHeightPx),
                )
            } else {
                0
            }
            val tabRowHeightPx = if (emojiMode) {
                minOf(desiredTabHeightPx, (heightAfterToolPx - contentHeightPx).coerceAtLeast(0))
            } else {
                minOf(desiredTabHeightPx, heightAfterToolPx)
            }
            val resolvedContentHeightPx = if (emojiMode) {
                contentHeightPx
            } else {
                (compactHeightPx - tabRowHeightPx - toolRowHeightPx).coerceAtLeast(0)
            }

            return ExpressionLayoutMetrics(
                itemSizePx = itemSizePx,
                expandedItemSizePx = expandedItemSizePx,
                itemGapPx = itemGapPx,
                horizontalPaddingPx = horizontalPaddingPx,
                tabRowHeightPx = tabRowHeightPx,
                contentHeightPx = resolvedContentHeightPx,
                toolRowHeightPx = toolRowHeightPx,
                actionWidthPx = px(MINIMUM_TOUCH_TARGET_DP * 2),
                actionHeightPx = minOf(px(MINIMUM_TOUCH_TARGET_DP), tabRowHeightPx),
                visibleItemCount = visibleItemCount,
                compactPanelHeightPx = compactHeightPx,
                minimumCompactPanelHeightPx = minimumCompactHeightPx,
                maximumCompactPanelHeightPx = maximumCompactHeightPx,
            )
        }

        private const val REFERENCE_WIDTH_PX = 443f
        private const val EXPANDED_COLUMNS = 3
        private const val MINIMUM_CONTENT_HEIGHT_DP = 48f
        private const val MINIMUM_TOUCH_TARGET_DP = 44f
        private const val MINIMUM_EMOJI_TWO_LAYER_DP = MINIMUM_TOUCH_TARGET_DP * 2
    }
}
