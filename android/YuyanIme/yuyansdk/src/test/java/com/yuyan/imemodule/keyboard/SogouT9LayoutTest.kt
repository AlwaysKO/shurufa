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
            SogouT9Layout.visualRowTops.toFloatArray(),
            0.000001f,
        )
        assertArrayEquals(
            floatArrayOf(0.1750f, 0.1750f, 0.1750f, 0.0056f),
            SogouT9Layout.visualRowStartXs.toFloatArray(),
            0.000001f,
        )
    }

    @Test
    fun `主键和右列使用 APK 规格并对齐至同一终点`() {
        assertEquals(0.2167f, SogouT9Layout.VISUAL_MAIN_KEY_WIDTH, 0.000001f)
        assertEquals(0.24922f, SogouT9Layout.VISUAL_MAIN_KEY_HEIGHT, 0.000001f)
        assertEquals(0.1694f, SogouT9Layout.VISUAL_RIGHT_COLUMN_WIDTH, 0.000001f)
        assertEquals(0.9945f, SogouT9Layout.mainRowRightEdge, 0.000001f)
        assertEquals(0.9945f, SogouT9Layout.visualColumnRightEdges.last(), 0.000001f)
    }

    @Test
    fun `九宫格 APK 视觉规格与当前加载器兼容常量隔离`() {
        assertEquals(0.005f, SogouT9Layout.START_X, 0.000001f)
        assertEquals(0.17f, SogouT9Layout.SIDE_WIDTH, 0.000001f)
        assertEquals(0.21666667f, SogouT9Layout.MAIN_WIDTH, 0.000001f)
        assertEquals(0.245f, SogouT9Layout.ROW_HEIGHT, 0.000001f)
        assertEquals(0.735f, SogouT9Layout.SIDE_HEIGHT, 0.000001f)
        assertArrayEquals(
            floatArrayOf(0.17f, 0.165f, 0.32f, 0.165f, 0.17f),
            SogouT9Layout.bottomRowWidths.toFloatArray(),
            0.000001f,
        )
    }

    @Test
    fun `九宫格对外宽度快照不能改写全局规格`() {
        val runtimeSnapshot = SogouT9Layout.bottomRowWidths as MutableList<Float>
        val visualSnapshot = SogouT9Layout.visualBottomRowWidths as MutableList<Float>

        runtimeSnapshot[0] = 0f
        visualSnapshot[0] = 0f

        assertEquals(0.17f, SogouT9Layout.bottomRowWidths.first(), 0.000001f)
        assertEquals(0.1694f, SogouT9Layout.visualBottomRowWidths.first(), 0.000001f)
        assertEquals(0.1694f, SogouT9Layout.rowGeometry.last().keys.first().width, 0.000001f)
    }

    @Test
    fun `候选码区使用 APK 的完整矩形`() {
        assertEquals(0.0056f, SogouT9Layout.candidateCodeView.x, 0.000001f)
        assertEquals(0.0078f, SogouT9Layout.candidateCodeView.y, 0.000001f)
        assertEquals(0.1694f, SogouT9Layout.candidateCodeView.width, 0.000001f)
        assertEquals(0.7477f, SogouT9Layout.candidateCodeView.height, 0.000001f)
        assertEquals(0.1750f, SogouT9Layout.candidateCodeView.right, 0.000001f)
        assertEquals(0.7555f, SogouT9Layout.candidateCodeView.bottom, 0.000001f)
    }

    @Test
    fun `九宫格主区与底行逐键几何正确且不越界`() {
        val expectedWidths = listOf(
            List(3) { 0.2167f } + 0.1694f,
            List(3) { 0.2167f } + 0.1694f,
            List(3) { 0.2167f } + 0.1694f,
            listOf(0.1694f, 0.1630f, 0.3241f, 0.1630f, 0.1694f),
        )
        val expectedLefts = listOf(
            listOf(0.1750f, 0.3917f, 0.6084f, 0.8251f),
            listOf(0.1750f, 0.3917f, 0.6084f, 0.8251f),
            listOf(0.1750f, 0.3917f, 0.6084f, 0.8251f),
            listOf(0.0056f, 0.1750f, 0.3380f, 0.6621f, 0.8251f),
        )
        assertArrayEquals(intArrayOf(4, 4, 4, 5), SogouT9Layout.rowGeometry.map { it.keys.size }.toIntArray())
        SogouT9Layout.rowGeometry.forEachIndexed { rowIndex, row ->
            assertEquals(SogouT9Layout.visualRowTops[rowIndex], row.top, 0.000001f)
            row.keys.forEachIndexed { keyIndex, key ->
                assertEquals(expectedLefts[rowIndex][keyIndex], key.left, 0.000001f)
                assertEquals(expectedWidths[rowIndex][keyIndex], key.width, 0.000001f)
                assertTrue(key.left >= 0f)
                assertTrue(key.right <= 1f)
            }
        }
        SogouT9Layout.rowGeometry.take(3).forEach { row ->
            assertEquals(0.175f, row.keys.first().left, 0.000001f)
            assertEquals(0.2167f, row.keys.first().width, 0.000001f)
            assertEquals(0.1694f, row.keys.last().width, 0.000001f)
            assertEquals(0.24922f, row.keys.first().height, 0.000001f)
            assertEquals(0.9945f, row.keys.last().right, 0.000001f)
        }
        assertEquals(0.0056f, SogouT9Layout.rowGeometry.last().keys.first().left, 0.000001f)
        assertEquals(0.2368f, SogouT9Layout.rowGeometry.last().keys.first().height, 0.000001f)
        assertEquals(0.9945f, SogouT9Layout.rowGeometry.last().keys.last().right, 0.000001f)
    }

    @Test
    fun `九宫格触摸跨度在键中线连续覆盖`() {
        assertEquals(0f, SogouT9Layout.rowGeometry.first().touchTop, 0.000001f)
        assertEquals(1f, SogouT9Layout.rowGeometry.last().touchBottom, 0.000001f)
        SogouT9Layout.rowGeometry.forEachIndexed { rowIndex, row ->
            val expectedLeft = if (rowIndex < 3) 0.175f else 0f
            assertEquals(expectedLeft, row.keys.first().touchLeft, 0.000001f)
            assertEquals(1f, row.keys.last().touchRight, 0.000001f)
            row.keys.zipWithNext().forEach { (left, right) ->
                assertEquals(left.touchRight, right.touchLeft, 0.000001f)
                assertEquals((left.right + right.left) / 2f, left.touchRight, 0.000001f)
            }
        }
        SogouT9Layout.rowGeometry.zipWithNext().forEach { (upper, lower) ->
            assertEquals(upper.touchBottom, lower.touchTop, 0.000001f)
        }
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
            SogouT9Layout.visualBottomRowWidths.toFloatArray(),
            0.000001f,
        )
        assertEquals(0.2368f, SogouT9Layout.VISUAL_BOTTOM_ROW_HEIGHT, 0.000001f)
        assertEquals(0.9945f, SogouT9Layout.bottomRowRightEdge, 0.000001f)
    }

    @Test
    fun `底栏所有按键使用相同高度和规格宽度`() {
        val keys = SogouT9Layout.bottomRowCodes.map(::SoftKey)

        SogouT9Layout.applyBottomRowGeometry(keys)

        keys.forEachIndexed { index, key ->
            assertEquals(SogouT9Layout.bottomRowWidths[index], key.widthF, 0.0001f)
            assertEquals(SogouT9Layout.ROW_HEIGHT, key.heightF, 0.0001f)
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
