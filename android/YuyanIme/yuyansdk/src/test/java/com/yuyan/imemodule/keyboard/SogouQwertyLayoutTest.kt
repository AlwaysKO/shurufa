package com.yuyan.imemodule.keyboard

import android.view.KeyEvent
import com.yuyan.imemodule.entity.keyboard.LongPressAction
import com.yuyan.imemodule.manager.InputModeSwitcher
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SogouQwertyLayoutTest {
    @Test
    fun `三行字母键覆盖宽度并保持搜狗式错位`() {
        assertEquals(0.99f, SogouQwertyLayout.LETTER_WIDTH * 10, 0.0001f)
        assertEquals(0.055f, SogouQwertyLayout.SECOND_ROW_START_X, 0.0001f)
        assertEquals(SogouQwertyLayout.SHIFT_WIDTH, SogouQwertyLayout.DELETE_WIDTH, 0.0001f)
        assertEquals(
            0.991f,
            SogouQwertyLayout.SHIFT_WIDTH + SogouQwertyLayout.LETTER_WIDTH * 7 +
                SogouQwertyLayout.DELETE_WIDTH,
            0.0001f,
        )
        assertEquals(0.24f, SogouQwertyLayout.ROW_HEIGHT, 0.0001f)
        assertTrue(SogouQwertyLayout.X_MARGIN_SCALE < 1f)
    }

    @Test
    fun `底栏为搜狗式七键且宽度铺满`() {
        assertArrayEquals(
            intArrayOf(
                InputModeSwitcher.USER_KEYCODE_SYMBOL,
                InputModeSwitcher.USER_KEYCODE_NUMBER,
                InputModeSwitcher.USER_KEYCODE_LEFT_COMMA,
                KeyEvent.KEYCODE_SPACE,
                InputModeSwitcher.USER_KEYCODE_LEFT_PERIOD,
                InputModeSwitcher.USER_KEYCODE_LANG,
                KeyEvent.KEYCODE_ENTER,
            ),
            SogouQwertyLayout.bottomRowCodes,
        )
        assertEquals(0.99f, SogouQwertyLayout.bottomRowWidths.sum(), 0.0001f)
    }

    @Test
    fun `全键空格保持点击空格和长按语音`() {
        val key = SogouQwertyLayout.createVoiceSpaceKey()

        assertEquals(KeyEvent.KEYCODE_SPACE, key.code)
        assertEquals(LongPressAction.Voice, key.longPressAction)
        assertEquals(SogouQwertyLayout.ROW_HEIGHT, key.heightF, 0.0001f)
    }
}
