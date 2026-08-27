package com.yuyan.imemodule.keyboard

import android.view.KeyEvent
import com.yuyan.imemodule.entity.keyboard.LongPressAction
import com.yuyan.imemodule.entity.keyboard.SoftKey
import com.yuyan.imemodule.manager.InputModeSwitcher

/** 中文全键和英文全键共用的搜狗式几何规格。 */
object SogouQwertyLayout {
    /** APK 26.ini 中相对于键盘宽度的高度。 */
    const val KEYBOARD_HEIGHT_TO_WIDTH_RATIO = 0.5944f
    const val LETTER_WIDTH = 0.0907f
    const val ROW_HEIGHT = 0.2212f
    const val HORIZONTAL_GAP = 0.008333f
    const val SECOND_ROW_START_X = 0.0593f
    const val SHIFT_WIDTH = 0.1407f
    const val DELETE_WIDTH = 0.1398f
    const val X_MARGIN_SCALE = 0.7f
    const val Y_MARGIN_SCALE = 0.9f

    val rowTops = floatArrayOf(0.0093f, 0.2586f, 0.5078f, 0.7570f)
    val rowStartXs = floatArrayOf(0.0093f, SECOND_ROW_START_X, 0.0093f, 0.0093f)

    val bottomRowCodes = intArrayOf(
        InputModeSwitcher.USER_KEYCODE_SYMBOL,
        InputModeSwitcher.USER_KEYCODE_NUMBER,
        InputModeSwitcher.USER_KEYCODE_LEFT_COMMA,
        KeyEvent.KEYCODE_SPACE,
        InputModeSwitcher.USER_KEYCODE_LEFT_PERIOD,
        InputModeSwitcher.USER_KEYCODE_LANG,
        KeyEvent.KEYCODE_ENTER,
    )
    val bottomRowWidths = floatArrayOf(0.1407f, 0.1130f, 0.0870f, 0.2519f, 0.0870f, 0.1130f, 0.1398f)

    /** 按 26.ini 的 H_OFFSET、键宽和 H_GAP_QWERTY 计算指定行的右边界。 */
    fun rowRightEdge(rowIndex: Int): Float {
        require(rowIndex in rowStartXs.indices) { "rowIndex must be in ${rowStartXs.indices}" }
        val widths = when (rowIndex) {
            0 -> FloatArray(10) { LETTER_WIDTH }
            1 -> FloatArray(9) { LETTER_WIDTH }
            2 -> floatArrayOf(
                SHIFT_WIDTH,
                LETTER_WIDTH,
                LETTER_WIDTH,
                LETTER_WIDTH,
                LETTER_WIDTH,
                LETTER_WIDTH,
                LETTER_WIDTH,
                LETTER_WIDTH,
                DELETE_WIDTH,
            )
            else -> bottomRowWidths
        }
        return rowStartXs[rowIndex] + widths.sum() + HORIZONTAL_GAP * (widths.size - 1)
    }

    fun createVoiceSpaceKey() = SoftKey(code = KeyEvent.KEYCODE_SPACE).apply {
        heightF = ROW_HEIGHT
        longPressAction = LongPressAction.Voice
    }
}
