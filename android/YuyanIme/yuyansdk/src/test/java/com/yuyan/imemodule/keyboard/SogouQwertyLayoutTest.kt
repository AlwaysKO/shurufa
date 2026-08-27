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
    fun `26键使用 APK 的键盘高度与四行起点`() {
        assertEquals(0.5944f, SogouQwertyLayout.KEYBOARD_HEIGHT_TO_WIDTH_RATIO, 0.000001f)
        assertArrayEquals(
            floatArrayOf(0.0093f, 0.2586f, 0.5078f, 0.7570f),
            SogouQwertyLayout.rowTops,
            0.000001f,
        )
        assertArrayEquals(
            floatArrayOf(0.0093f, 0.0593f, 0.0093f, 0.0093f),
            SogouQwertyLayout.rowStartXs,
            0.000001f,
        )
    }

    @Test
    fun `26键普通键使用 APK 的视觉宽高与水平间隔`() {
        assertEquals(0.0907f, SogouQwertyLayout.LETTER_WIDTH, 0.000001f)
        assertEquals(0.2212f, SogouQwertyLayout.ROW_HEIGHT, 0.000001f)
        assertEquals(0.008333f, SogouQwertyLayout.HORIZONTAL_GAP, 0.000001f)
        assertTrue(SogouQwertyLayout.HORIZONTAL_GAP < SogouQwertyLayout.LETTER_WIDTH)
    }

    @Test
    fun `Shift 和删除键保留 APK 中不同的宽度`() {
        assertEquals(0.1407f, SogouQwertyLayout.SHIFT_WIDTH, 0.000001f)
        assertEquals(0.1398f, SogouQwertyLayout.DELETE_WIDTH, 0.000001f)
        assertEquals(0.991364f, SogouQwertyLayout.rowRightEdge(2), 0.000001f)
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
        assertArrayEquals(
            floatArrayOf(0.1407f, 0.1130f, 0.0870f, 0.2519f, 0.0870f, 0.1130f, 0.1398f),
            SogouQwertyLayout.bottomRowWidths,
            0.000001f,
        )
        assertEquals(0.991698f, SogouQwertyLayout.rowRightEdge(3), 0.000001f)
    }

    @Test
    fun `全键空格保持点击空格和长按语音`() {
        val key = SogouQwertyLayout.createVoiceSpaceKey()

        assertEquals(KeyEvent.KEYCODE_SPACE, key.code)
        assertEquals(LongPressAction.Voice, key.longPressAction)
        assertEquals(SogouQwertyLayout.ROW_HEIGHT, key.heightF, 0.0001f)
    }
}
