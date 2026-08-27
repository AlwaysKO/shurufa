package com.yuyan.imemodule.keyboard

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import com.yuyan.imemodule.entity.keyboard.LongPressAction
import com.yuyan.imemodule.entity.keyboard.SoftKey
import com.yuyan.imemodule.manager.InputModeSwitcher
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SogouT9LayoutTest {
    @Test
    fun `九宫格使用 APK 的键盘高度与四行起点`() {
        assertEquals(0.5944f, SogouT9Layout.KEYBOARD_HEIGHT_TO_WIDTH_RATIO, 0.000001f)
        assertArrayEquals(
            floatArrayOf(0.0078f, 0.2570f, 0.5062f, 0.7555f),
            SogouT9Layout.rowTops,
            0.000001f,
        )
        assertArrayEquals(
            floatArrayOf(0.1750f, 0.1750f, 0.1750f, 0.0056f),
            SogouT9Layout.rowStartXs,
            0.000001f,
        )
    }

    @Test
    fun `主键和右列使用 APK 规格并对齐至同一终点`() {
        assertEquals(0.2167f, SogouT9Layout.MAIN_WIDTH, 0.000001f)
        assertEquals(0.24922f, SogouT9Layout.ROW_HEIGHT, 0.000001f)
        assertEquals(0.1694f, SogouT9Layout.RIGHT_COLUMN_WIDTH, 0.000001f)
        assertEquals(0.9945f, SogouT9Layout.mainRowRightEdge, 0.000001f)
        assertEquals(0.9945f, SogouT9Layout.columnRightEdges.last(), 0.000001f)
    }

    @Test
    fun `候选码区使用 APK 的完整矩形`() {
        assertEquals(0.0056f, SogouT9Layout.candidateCodeView.x, 0.000001f)
        assertEquals(0.0078f, SogouT9Layout.candidateCodeView.y, 0.000001f)
        assertEquals(0.1694f, SogouT9Layout.candidateCodeView.width, 0.000001f)
        assertEquals(0.7477f, SogouT9Layout.candidateCodeView.height, 0.000001f)
        assertEquals(0.1750f, SogouT9Layout.candidateCodeView.right, 0.000001f)
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
        assertArrayEquals(
            floatArrayOf(0.1694f, 0.1630f, 0.3241f, 0.1630f, 0.1694f),
            SogouT9Layout.bottomRowWidths,
            0.000001f,
        )
        assertEquals(0.2368f, SogouT9Layout.BOTTOM_ROW_HEIGHT, 0.000001f)
        assertEquals(0.9945f, SogouT9Layout.bottomRowRightEdge, 0.000001f)
    }

    @Test
    fun `底栏所有按键使用相同高度和规格宽度`() {
        val keys = SogouT9Layout.bottomRowCodes.map(::SoftKey)

        SogouT9Layout.applyBottomRowGeometry(keys)

        keys.forEachIndexed { index, key ->
            assertEquals(SogouT9Layout.bottomRowWidths[index], key.widthF, 0.0001f)
            assertEquals(SogouT9Layout.BOTTOM_ROW_HEIGHT, key.heightF, 0.0001f)
        }
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
