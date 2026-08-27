package com.yuyan.imemodule.keyboard

import android.view.KeyEvent
import com.yuyan.imemodule.entity.keyboard.LongPressAction
import com.yuyan.imemodule.entity.keyboard.SoftKey
import com.yuyan.imemodule.entity.keyboard.SoftKeyToggle
import com.yuyan.imemodule.entity.keyboard.ToggleState
import com.yuyan.imemodule.manager.InputModeSwitcher

/** 中文九宫格的搜狗式几何与键位规格。 */
object SogouT9Layout {
    data class NormalizedRect(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
    ) {
        val right: Float get() = x + width
        val bottom: Float get() = y + height
    }

    /** 应用层原有的屏幕高度比例，保留供偏好设置使用。 */
    const val KEYBOARD_HEIGHT_RATIO = 0.278f
    /** APK 9.ini 中相对于键盘宽度的高度。 */
    const val KEYBOARD_HEIGHT_TO_WIDTH_RATIO = 0.5944f
    const val CANDIDATE_TEXT_SIZE_PERCENT = 45
    const val START_X = 0.0056f
    const val MAIN_START_X = 0.175f
    const val RIGHT_COLUMN_WIDTH = 0.1694f
    const val SIDE_WIDTH = RIGHT_COLUMN_WIDTH
    const val MAIN_WIDTH = 0.2167f
    const val ROW_HEIGHT = 0.24922f
    const val SIDE_HEIGHT = 0.7477f
    const val BOTTOM_ROW_HEIGHT = 0.2368f
    const val X_MARGIN_SCALE = 0.7f
    const val Y_MARGIN_SCALE = 0.8f

    val rowTops = floatArrayOf(0.0078f, 0.2570f, 0.5062f, 0.7555f)
    val rowStartXs = floatArrayOf(MAIN_START_X, MAIN_START_X, MAIN_START_X, START_X)
    val candidateCodeView = NormalizedRect(
        x = START_X,
        y = rowTops[0],
        width = SIDE_WIDTH,
        height = SIDE_HEIGHT,
    )

    val columnWidths = floatArrayOf(SIDE_WIDTH, MAIN_WIDTH, MAIN_WIDTH, MAIN_WIDTH, SIDE_WIDTH)
    val columnLeftEdges = columnWidths.runningFold(START_X) { left, width -> left + width }
        .dropLast(1)
        .toFloatArray()
    val columnRightEdges = columnWidths.runningFold(START_X) { left, width -> left + width }
        .drop(1)
        .toFloatArray()

    val rightColumnCodes = intArrayOf(KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_CLEAR, KeyEvent.KEYCODE_0)
    val keyRows = arrayOf(
        intArrayOf(
            InputModeSwitcher.USER_KEYCODE_LEFT_SYMBOL,
            KeyEvent.KEYCODE_APOSTROPHE,
            KeyEvent.KEYCODE_A,
            KeyEvent.KEYCODE_D,
            rightColumnCodes[0],
        ),
        intArrayOf(KeyEvent.KEYCODE_G, KeyEvent.KEYCODE_J, KeyEvent.KEYCODE_M, rightColumnCodes[1]),
        intArrayOf(KeyEvent.KEYCODE_P, KeyEvent.KEYCODE_T, KeyEvent.KEYCODE_W, rightColumnCodes[2]),
    )

    val bottomRowCodes = intArrayOf(
        InputModeSwitcher.USER_KEYCODE_SYMBOL,
        InputModeSwitcher.USER_KEYCODE_NUMBER,
        KeyEvent.KEYCODE_SPACE,
        InputModeSwitcher.USER_KEYCODE_LANG,
        KeyEvent.KEYCODE_ENTER,
    )
    val bottomRowWidths = floatArrayOf(0.1694f, 0.1630f, 0.3241f, 0.1630f, 0.1694f)
    val mainRowRightEdge: Float
        get() = MAIN_START_X + MAIN_WIDTH * 3 + RIGHT_COLUMN_WIDTH
    val bottomRowRightEdge: Float
        get() = START_X + bottomRowWidths.sum()

    fun applyBottomRowGeometry(keys: List<SoftKey>) {
        require(keys.size == bottomRowWidths.size)
        keys.forEachIndexed { index, key ->
            key.widthF = bottomRowWidths[index]
            key.heightF = BOTTOM_ROW_HEIGHT
        }
    }

    fun createVoiceSpaceKey() = SoftKey(code = KeyEvent.KEYCODE_SPACE).apply {
        heightF = BOTTOM_ROW_HEIGHT
        longPressAction = LongPressAction.Voice
    }

    fun createEnterKey() = SoftKeyToggle(KeyEvent.KEYCODE_ENTER).apply {
        heightF = BOTTOM_ROW_HEIGHT
        stateId = 0
        preferTextLabel = true
        setToggleStates(
            listOf(
                ToggleState("换行", 0),
                ToggleState("换行", 1),
                ToggleState("去往", 2),
                ToggleState("搜索", 3),
                ToggleState("发送", 4),
                ToggleState("下一个", 5),
                ToggleState("完成", 6),
                ToggleState("上一个", 7),
            )
        )
    }
}
