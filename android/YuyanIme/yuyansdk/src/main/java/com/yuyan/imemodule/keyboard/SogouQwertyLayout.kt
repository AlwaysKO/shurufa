package com.yuyan.imemodule.keyboard

import android.view.KeyEvent
import com.yuyan.imemodule.entity.keyboard.LongPressAction
import com.yuyan.imemodule.entity.keyboard.SoftKey
import com.yuyan.imemodule.manager.InputModeSwitcher

/** 中文全键和英文全键共用的搜狗式几何规格。 */
object SogouQwertyLayout {
    /** APK 26.ini 中相对于键盘宽度的高度。 */
    const val KEYBOARD_HEIGHT_TO_WIDTH_RATIO = 0.5944f
    const val VISUAL_KEY_WIDTH = 0.0907f
    const val VISUAL_KEY_HEIGHT = 0.2212f
    const val VISUAL_HORIZONTAL_GAP = 0.008333f
    const val VISUAL_SECOND_ROW_START_X = 0.0593f
    const val VISUAL_SHIFT_WIDTH = 0.1407f
    const val VISUAL_DELETE_WIDTH = 0.1398f

    /** KeyboardLoaderUtil 尚未使用视觉间隔时的运行时兼容常量。 */
    const val LETTER_WIDTH = 0.099f
    const val ROW_HEIGHT = 0.24f
    const val SECOND_ROW_START_X = 0.055f
    const val SHIFT_WIDTH = 0.149f
    const val DELETE_WIDTH = 0.149f

    /** 项目 SoftKey 背景内边距的渲染兼容值，不是 APK ini 几何。 */
    const val X_MARGIN_SCALE = 0.7f
    const val Y_MARGIN_SCALE = 0.9f

    val rowTops = floatArrayOf(0.0093f, 0.2586f, 0.5078f, 0.7570f)
    val rowStartXs = floatArrayOf(0.0093f, VISUAL_SECOND_ROW_START_X, 0.0093f, 0.0093f)

    val bottomRowCodes = intArrayOf(
        InputModeSwitcher.USER_KEYCODE_SYMBOL,
        InputModeSwitcher.USER_KEYCODE_NUMBER,
        InputModeSwitcher.USER_KEYCODE_LEFT_COMMA,
        KeyEvent.KEYCODE_SPACE,
        InputModeSwitcher.USER_KEYCODE_LEFT_PERIOD,
        InputModeSwitcher.USER_KEYCODE_LANG,
        KeyEvent.KEYCODE_ENTER,
    )
    val officialBottomRowWidths = floatArrayOf(
        0.1407f,
        0.1130f,
        0.0870f,
        0.2519f,
        0.0870f,
        0.1130f,
        0.1398f,
    )

    /** KeyboardLoaderUtil 当前连续排列所需的运行时宽度。 */
    val bottomRowWidths = floatArrayOf(0.15f, 0.12f, 0.095f, 0.26f, 0.095f, 0.12f, 0.15f)

    val rowGeometry: List<RowGeometry> = buildKeyboardGeometry(
        listOf(
            RowGeometrySpec(
                top = rowTops[0],
                startX = rowStartXs[0],
                keyWidths = List(10) { VISUAL_KEY_WIDTH },
                keyHeight = VISUAL_KEY_HEIGHT,
                horizontalGap = VISUAL_HORIZONTAL_GAP,
            ),
            RowGeometrySpec(
                top = rowTops[1],
                startX = rowStartXs[1],
                keyWidths = List(9) { VISUAL_KEY_WIDTH },
                keyHeight = VISUAL_KEY_HEIGHT,
                horizontalGap = VISUAL_HORIZONTAL_GAP,
            ),
            RowGeometrySpec(
                top = rowTops[2],
                startX = rowStartXs[2],
                keyWidths = listOf(VISUAL_SHIFT_WIDTH) + List(7) { VISUAL_KEY_WIDTH } + VISUAL_DELETE_WIDTH,
                keyHeight = VISUAL_KEY_HEIGHT,
                horizontalGap = VISUAL_HORIZONTAL_GAP,
            ),
            RowGeometrySpec(
                top = rowTops[3],
                startX = rowStartXs[3],
                keyWidths = officialBottomRowWidths.toList(),
                keyHeight = VISUAL_KEY_HEIGHT,
                horizontalGap = VISUAL_HORIZONTAL_GAP,
            ),
        )
    )

    /** 按 26.ini 的 H_OFFSET、键宽和 H_GAP_QWERTY 计算指定行的右边界。 */
    fun rowRightEdge(rowIndex: Int): Float {
        require(rowIndex in rowGeometry.indices) { "rowIndex must be in ${rowGeometry.indices}" }
        return rowGeometry[rowIndex].keys.last().right
    }

    fun createVoiceSpaceKey() = SoftKey(code = KeyEvent.KEYCODE_SPACE).apply {
        heightF = ROW_HEIGHT
        longPressAction = LongPressAction.Voice
    }
}
