package com.yuyan.imemodule.keyboard

import android.view.KeyEvent
import com.yuyan.imemodule.manager.InputModeSwitcher
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SogouT9LayoutTest {
    @Test
    fun mainColumnsFillKeyboardWidth() {
        assertEquals(0.005f, SogouT9Layout.columnLeftEdges.first(), 0.0001f)
        assertEquals(0.995f, SogouT9Layout.columnRightEdges.last(), 0.0001f)
        assertEquals(0.17f, SogouT9Layout.columnWidths.first(), 0.0001f)
        assertEquals(0.17f, SogouT9Layout.columnWidths.last(), 0.0001f)
        assertEquals(0.21666667f, SogouT9Layout.columnWidths[1], 0.0001f)
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
}
