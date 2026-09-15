package com.yuyan.imemodule.keyboard

object QuickKeyboardPanelMetrics {
    const val REFERENCE_WIDTH = 1080
    const val INPUT_COLUMN_COUNT = 5
    const val THEME_COLUMN_COUNT = 2
    const val THEME_CARD_WIDTH_BASE = 474
    const val THEME_CARD_HEIGHT_BASE = 428
    const val THEME_CARD_ASPECT_RATIO = 474f / 428f
    const val CARD_RADIUS_BASE = 36
    const val SELECTED_STROKE_BASE = 4
    const val THEME_TITLE_TEXT_BASE = 36
    const val THEME_SIDE_PADDING_BASE = 55
    const val THEME_CARD_GAP_BASE = 24
    const val NAVIGATION_HEIGHT_DP = 56
    const val MIN_TOUCH_TARGET_DP = 44
    const val PANEL_SIDE_PADDING_DP = 10
    const val THEME_CARD_GAP_DP = 8

    private fun scale(referenceValue: Int, keyboardWidth: Int): Int =
        (referenceValue.toLong() * keyboardWidth / REFERENCE_WIDTH).toInt().coerceAtLeast(1)

    fun themeSidePadding(keyboardWidth: Int) = scale(THEME_SIDE_PADDING_BASE, keyboardWidth)
    fun themeCardGap(keyboardWidth: Int) = scale(THEME_CARD_GAP_BASE, keyboardWidth)
    fun themeCardWidth(keyboardWidth: Int) = scale(THEME_CARD_WIDTH_BASE, keyboardWidth)
    fun themeCardHeight(keyboardWidth: Int) = scale(THEME_CARD_HEIGHT_BASE, keyboardWidth)
    fun themeCardRadius(keyboardWidth: Int) = scale(CARD_RADIUS_BASE, keyboardWidth)
    fun themeSelectedStroke(keyboardWidth: Int) = scale(SELECTED_STROKE_BASE, keyboardWidth)
    fun themeTitleTextSize(keyboardWidth: Int) = THEME_TITLE_TEXT_BASE * keyboardWidth / REFERENCE_WIDTH.toFloat()
}
