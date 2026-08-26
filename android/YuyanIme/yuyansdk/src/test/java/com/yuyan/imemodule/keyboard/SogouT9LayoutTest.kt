package com.yuyan.imemodule.keyboard

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import com.yuyan.imemodule.entity.keyboard.LongPressAction
import com.yuyan.imemodule.manager.InputModeSwitcher
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SogouT9LayoutTest {
    @Test
    fun mainColumnsFillKeyboardWidth() {
        assertEquals(0.005f, SogouT9Layout.columnLeftEdges.first(), 0.0001f)
        assertEquals(0.995f, SogouT9Layout.columnRightEdges.last(), 0.0001f)
        assertEquals(0.17f, SogouT9Layout.columnWidths.first(), 0.0001f)
        assertEquals(0.17f, SogouT9Layout.columnWidths.last(), 0.0001f)
        assertEquals(0.21666667f, SogouT9Layout.columnWidths[1], 0.0001f)
        assertEquals(0.245f, SogouT9Layout.ROW_HEIGHT, 0.0001f)
        assertEquals(0.735f, SogouT9Layout.SIDE_HEIGHT, 0.0001f)
        assertEquals(0.8f, SogouT9Layout.Y_MARGIN_SCALE, 0.0001f)
    }

    @Test
    fun rightColumnAndBottomRowMatchSogou() {
        assertArrayEquals(
            intArrayOf(KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_CLEAR, KeyEvent.KEYCODE_0),
            SogouT9Layout.rightColumnCodes,
        )
        assertArrayEquals(
            intArrayOf(
                InputModeSwitcher.USER_KEYCODE_SYMBOL,
                InputModeSwitcher.USER_KEYCODE_NUMBER,
                KeyEvent.KEYCODE_SPACE,
                InputModeSwitcher.USER_KEYCODE_LANG,
                KeyEvent.KEYCODE_ENTER,
            ),
            SogouT9Layout.bottomRowCodes,
        )
        assertEquals(0.99f, SogouT9Layout.bottomRowWidths.sum(), 0.0001f)
    }

    @Test
    fun centerBottomKeyUsesSpaceForTapAndVoiceForLongPress() {
        val key = SogouT9Layout.createVoiceSpaceKey()

        assertEquals(KeyEvent.KEYCODE_SPACE, key.code)
        assertEquals(LongPressAction.Voice, key.longPressAction)
    }

    @Test
    fun normalEnterKeyShowsLineBreakText() {
        val key = SogouT9Layout.createEnterKey()

        assertEquals(KeyEvent.KEYCODE_ENTER, key.code)
        assertEquals("换行", key.keyLabel)
        assertTrue(key.preferTextLabel)
    }

    @Test
    fun imeActionNoneAlsoShowsLineBreakText() {
        val key = SogouT9Layout.createEnterKey()

        key.enableToggleState(EditorInfo.IME_ACTION_NONE)

        assertEquals("换行", key.keyLabel)
    }
}
