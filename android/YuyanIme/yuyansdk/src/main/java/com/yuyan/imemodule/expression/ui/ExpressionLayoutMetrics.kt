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

            val desiredTabHeightPx = px(COMPACT_ACTION_DP)
            val contentTopGapPx = px(CONTENT_TOP_GAP_DP)
            val desiredContentHeightPx =
                (if (emojiMode) px(MINIMUM_EMOJI_TWO_LAYER_DP) else itemSizePx) + contentTopGapPx
            // 恢复按钮已迁入候选拼音行，不再占用面板高度。
            val desiredToolHeightPx = 0
            val designedMaximumHeightPx =
                desiredTabHeightPx + desiredContentHeightPx + desiredToolHeightPx
            val designedMinimumHeightPx =
                desiredTabHeightPx + px(MINIMUM_CONTENT_HEIGHT_DP) + desiredToolHeightPx
            val heightBudgetPx = if (availableHeightPx == Int.MAX_VALUE) {
                designedMaximumHeightPx
            } else {
                (availableHeightPx - reservedKeyboardHeightPx).coerceAtLeast(0)
            }
            // 极小视口严格让位于候选行和键盘，不保留幽灵工具高度。
            val maximumCompactHeightPx = minOf(
                designedMaximumHeightPx,
                maxOf(heightBudgetPx, desiredToolHeightPx),
            )
            val minimumCompactHeightPx = minOf(designedMinimumHeightPx, maximumCompactHeightPx)
            val compactHeightPx = maximumCompactHeightPx

            val toolRowHeightPx = desiredToolHeightPx
            val heightAfterToolPx = (compactHeightPx - toolRowHeightPx).coerceAtLeast(0)
            val minimumEmojiContentPx = px(MINIMUM_TOUCH_TARGET_DP) + contentTopGapPx
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
                actionWidthPx = px(COMPACT_ACTION_DP * 2),
                actionHeightPx = (minOf(px(COMPACT_ACTION_DP), tabRowHeightPx) - px(ACTION_VERTICAL_MARGIN_DP) * 2).coerceAtLeast(0),
                visibleItemCount = visibleItemCount,
                compactPanelHeightPx = compactHeightPx,
                minimumCompactPanelHeightPx = minimumCompactHeightPx,
                maximumCompactPanelHeightPx = maximumCompactHeightPx,
            )
        }

        // 类似 CSS width/height，单位 dp；两颗按钮总宽度 = COMPACT_ACTION_DP × 2。
        const val COMPACT_ACTION_DP = 32f
        const val ACTION_VERTICAL_MARGIN_DP = 3f
        const val CONTENT_TOP_GAP_DP = 4f
        private const val REFERENCE_WIDTH_PX = 443f
        private const val EXPANDED_COLUMNS = 3
        private const val MINIMUM_CONTENT_HEIGHT_DP = 48f
        private const val MINIMUM_TOUCH_TARGET_DP = 44f
        private const val MINIMUM_EMOJI_TWO_LAYER_DP = MINIMUM_TOUCH_TARGET_DP * 2
    }
}
