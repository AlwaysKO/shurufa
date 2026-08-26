package com.yuyan.imemodule.keyboard

import android.view.KeyEvent
import com.yuyan.imemodule.entity.keyboard.LongPressAction
import com.yuyan.imemodule.entity.keyboard.SoftKey
import com.yuyan.imemodule.manager.InputModeSwitcher

/** 中文全键和英文全键共用的搜狗式几何规格。 */
object SogouQwertyLayout {
    const val LETTER_WIDTH = 0.099f
    const val SECOND_ROW_START_X = 0.055f
    const val SHIFT_WIDTH = 0.149f
    const val DELETE_WIDTH = 0.149f
    const val ROW_HEIGHT = 0.24f
    const val X_MARGIN_SCALE = 0.7f
    const val Y_MARGIN_SCALE = 0.9f

    val bottomRowCodes = intArrayOf(
        InputModeSwitcher.USER_KEYCODE_SYMBOL,
        InputModeSwitcher.USER_KEYCODE_NUMBER,
        InputModeSwitcher.USER_KEYCODE_LEFT_COMMA,
        KeyEvent.KEYCODE_SPACE,
        InputModeSwitcher.USER_KEYCODE_LEFT_PERIOD,
        InputModeSwitcher.USER_KEYCODE_LANG,
        KeyEvent.KEYCODE_ENTER,
    )
    val bottomRowWidths = floatArrayOf(0.15f, 0.12f, 0.095f, 0.26f, 0.095f, 0.12f, 0.15f)

    fun createVoiceSpaceKey() = SoftKey(code = KeyEvent.KEYCODE_SPACE).apply {
        heightF = ROW_HEIGHT
        longPressAction = LongPressAction.Voice
    }
}
