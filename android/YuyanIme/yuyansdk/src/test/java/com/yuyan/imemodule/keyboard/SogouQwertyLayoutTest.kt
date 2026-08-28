package com.yuyan.imemodule.keyboard

import android.view.KeyEvent
import com.yuyan.imemodule.entity.keyboard.LongPressAction
import com.yuyan.imemodule.manager.InputModeSwitcher
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SogouQwertyLayoutTest {
    @Test
    fun `26键使用 APK 的键盘高度与四行起点`() {
        assertEquals(0.5944f, SogouQwertyLayout.KEYBOARD_HEIGHT_TO_WIDTH_RATIO, 0.000001f)
        assertArrayEquals(
            floatArrayOf(0.0093f, 0.2586f, 0.5078f, 0.7570f),
            SogouQwertyLayout.visualRowTops.toFloatArray(),
            0.000001f,
        )
        assertArrayEquals(
            floatArrayOf(0.0093f, 0.0593f, 0.0093f, 0.0093f),
            SogouQwertyLayout.visualRowStartXs.toFloatArray(),
            0.000001f,
        )
    }

    @Test
    fun `26键 APK 视觉规格与当前加载器兼容常量隔离`() {
        assertEquals(0.0907f, SogouQwertyLayout.VISUAL_KEY_WIDTH, 0.000001f)
        assertEquals(0.2212f, SogouQwertyLayout.VISUAL_KEY_HEIGHT, 0.000001f)
        assertEquals(0.008333f, SogouQwertyLayout.VISUAL_HORIZONTAL_GAP, 0.000001f)
        assertEquals(0.099f, SogouQwertyLayout.LETTER_WIDTH, 0.000001f)
        assertEquals(0.24f, SogouQwertyLayout.ROW_HEIGHT, 0.000001f)
        assertEquals(0.055f, SogouQwertyLayout.SECOND_ROW_START_X, 0.000001f)
        assertTrue(SogouQwertyLayout.VISUAL_HORIZONTAL_GAP < SogouQwertyLayout.VISUAL_KEY_WIDTH)
    }

    @Test
    fun `26键对外宽度快照不能改写全局规格`() {
        val runtimeSnapshot = SogouQwertyLayout.bottomRowWidths
        val visualSnapshot = SogouQwertyLayout.visualBottomRowWidths

        assertNotSame(runtimeSnapshot, SogouQwertyLayout.bottomRowWidths)
        assertNotSame(visualSnapshot, SogouQwertyLayout.visualBottomRowWidths)

        assertEquals(0.15f, SogouQwertyLayout.bottomRowWidths.first(), 0.000001f)
        assertEquals(0.1407f, SogouQwertyLayout.visualBottomRowWidths.first(), 0.000001f)
        assertEquals(0.1407f, SogouQwertyLayout.rowGeometry.last().keys.first().width, 0.000001f)
    }

    @Test
    fun `26键键码副本不能改写全局规格`() {
        val codes = SogouQwertyLayout.bottomRowCodes

        codes[0] = KeyEvent.KEYCODE_UNKNOWN

        assertEquals(InputModeSwitcher.USER_KEYCODE_SYMBOL, SogouQwertyLayout.bottomRowCodes[0])
    }

    @Test
    fun `Shift 和删除键保留 APK 中不同的宽度`() {
        assertEquals(0.1407f, SogouQwertyLayout.VISUAL_SHIFT_WIDTH, 0.000001f)
        assertEquals(0.1398f, SogouQwertyLayout.VISUAL_DELETE_WIDTH, 0.000001f)
        assertEquals(0.149f, SogouQwertyLayout.SHIFT_WIDTH, 0.000001f)
        assertEquals(0.149f, SogouQwertyLayout.DELETE_WIDTH, 0.000001f)
        assertEquals(0.991364f, SogouQwertyLayout.rowRightEdge(2), 0.000001f)
    }

    @Test
    fun `26键四行逐键几何保留视觉间隔且不越界`() {
        val expectedWidths = listOf(
            List(10) { 0.0907f },
            List(9) { 0.0907f },
            listOf(0.1407f) + List(7) { 0.0907f } + 0.1398f,
            listOf(0.1407f, 0.1130f, 0.0870f, 0.2519f, 0.0870f, 0.1130f, 0.1398f),
        )
        val expectedLefts = listOf(
            listOf(0.009300f, 0.108333f, 0.207366f, 0.306399f, 0.405432f, 0.504465f, 0.603498f, 0.702531f, 0.801564f, 0.900597f),
            listOf(0.059300f, 0.158333f, 0.257366f, 0.356399f, 0.455432f, 0.554465f, 0.653498f, 0.752531f, 0.851564f),
            listOf(0.009300f, 0.158333f, 0.257366f, 0.356399f, 0.455432f, 0.554465f, 0.653498f, 0.752531f, 0.851564f),
            listOf(0.009300f, 0.158333f, 0.279666f, 0.374999f, 0.635232f, 0.730565f, 0.851898f),
        )
        assertArrayEquals(intArrayOf(10, 9, 9, 7), SogouQwertyLayout.rowGeometry.map { it.keys.size }.toIntArray())
        SogouQwertyLayout.rowGeometry.forEachIndexed { rowIndex, row ->
            assertEquals(SogouQwertyLayout.visualRowTops[rowIndex], row.top, 0.000001f)
            row.keys.forEachIndexed { keyIndex, key ->
                assertEquals(expectedLefts[rowIndex][keyIndex], key.left, 0.000001f)
                assertEquals(expectedWidths[rowIndex][keyIndex], key.width, 0.000001f)
                assertEquals(SogouQwertyLayout.VISUAL_KEY_HEIGHT, key.height, 0.000001f)
                assertTrue(key.left >= 0f)
                assertTrue(key.right <= 1f)
            }
            row.keys.zipWithNext().forEach { (left, right) ->
                assertEquals(SogouQwertyLayout.VISUAL_HORIZONTAL_GAP, right.left - left.right, 0.000001f)
            }
        }
        assertEquals(0.0093f, SogouQwertyLayout.rowGeometry[0].keys.first().left, 0.000001f)
        assertEquals(0.0593f, SogouQwertyLayout.rowGeometry[1].keys.first().left, 0.000001f)
        assertEquals(0.1407f, SogouQwertyLayout.rowGeometry[2].keys.first().width, 0.000001f)
        assertEquals(0.1398f, SogouQwertyLayout.rowGeometry[2].keys.last().width, 0.000001f)
    }

    @Test
    fun `26键触摸跨度在视觉间隔中线连续覆盖`() {
        assertEquals(0f, SogouQwertyLayout.rowGeometry.first().touchTop, 0.000001f)
        assertEquals(1f, SogouQwertyLayout.rowGeometry.last().touchBottom, 0.000001f)
        SogouQwertyLayout.rowGeometry.forEach { row ->
            assertEquals(0f, row.keys.first().touchLeft, 0.000001f)
            assertEquals(1f, row.keys.last().touchRight, 0.000001f)
            row.keys.zipWithNext().forEach { (left, right) ->
                val gapMiddle = (left.right + right.left) / 2f
                assertEquals(gapMiddle, left.touchRight, 0.000001f)
                assertEquals(left.touchRight, right.touchLeft, 0.000001f)
            }
        }
        SogouQwertyLayout.rowGeometry.zipWithNext().forEach { (upper, lower) ->
            assertEquals(upper.touchBottom, lower.touchTop, 0.000001f)
        }
    }

    @Test
    fun `底栏使用七键且宽度铺满`() {
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
            SogouQwertyLayout.visualBottomRowWidths.toFloatArray(),
            0.000001f,
        )
        assertArrayEquals(
            floatArrayOf(0.15f, 0.12f, 0.095f, 0.26f, 0.095f, 0.12f, 0.15f),
            SogouQwertyLayout.bottomRowWidths.toFloatArray(),
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
