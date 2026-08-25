package com.yuyan.imemodule.keyboard

import android.view.KeyEvent
import com.yuyan.imemodule.entity.keyboard.LongPressAction
import com.yuyan.imemodule.entity.keyboard.SoftKey
import com.yuyan.imemodule.entity.keyboard.SoftKeyToggle
import com.yuyan.imemodule.entity.keyboard.ToggleState
import com.yuyan.imemodule.manager.InputModeSwitcher

/** 中文九宫格的搜狗式几何与键位规格。 */
object SogouT9Layout {
    const val KEYBOARD_HEIGHT_RATIO = 0.278f
    const val CANDIDATE_TEXT_SIZE_PERCENT = 45
    const val START_X = 0.005f
    const val SIDE_WIDTH = 0.17f
    const val MAIN_WIDTH = 0.21666667f
    const val X_MARGIN_SCALE = 0.7f
    const val Y_MARGIN_SCALE = 0.6f

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
    val bottomRowWidths = floatArrayOf(0.17f, 0.165f, 0.32f, 0.165f, 0.17f)

    fun createVoiceSpaceKey() = SoftKey(code = KeyEvent.KEYCODE_SPACE).apply {
        longPressAction = LongPressAction.Voice
    }

    fun createEnterKey() = SoftKeyToggle(KeyEvent.KEYCODE_ENTER).apply {
        stateId = 0
        preferTextLabel = true
        setToggleStates(
            listOf(
                ToggleState("换行", 0),
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
