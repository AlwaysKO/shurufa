package com.yuyan.imemodule.keyboard

import android.content.Context
import android.view.KeyEvent
import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.entity.keyboard.SoftKeyboard
import com.yuyan.imemodule.entity.keyboard.SoftKey
import com.yuyan.imemodule.manager.InputModeSwitcher
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.singleton.EnvironmentSingleton
import com.yuyan.imemodule.utils.KeyboardLoaderUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SogouKeyboardHitMapTest {
    @Before
    fun initializePreferences() {
        val context = RuntimeEnvironment.getApplication()
        Launcher::class.java.getDeclaredField("context").apply {
            isAccessible = true
            set(Launcher.instance, context)
        }
        val preferences = context.getSharedPreferences("sogou-hit-map-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        AppPrefs.init(preferences)
        ThemeManager.init(context.resources.configuration)
        AppPrefs.getInstance().keyboardSetting.abcNumberLine.setValue(false)
        setEnvironmentDimension("skbWidth", TEST_WIDTH)
        setEnvironmentDimension("skbHeight", TEST_HEIGHT)
    }

    @Test
    fun `Loader 为中英文全键生成 APK 逐键视觉矩形`() {
        listOf(
            InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_PINYIN,
            InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_ABC,
        ).forEach { layout ->
            KeyboardLoaderUtil.instance.clearKeyboardMap()
            val keyboard = KeyboardLoaderUtil.instance.getSoftKeyboard(layout)
            val expectedRows = SogouQwertyLayout.rowGeometry

            assertEquals(listOf(10, 9, 9, 7), keyboard.mKeyRows.map { it.size })
            keyboard.mKeyRows.forEachIndexed { rowIndex, row ->
                row.forEachIndexed { keyIndex, key ->
                    val expected = expectedRows[rowIndex].keys[keyIndex]
                    assertEquals(expected.left, key.mLeftF, EPSILON)
                    assertEquals(expected.top, key.mTopF, EPSILON)
                    assertEquals(expected.width, key.widthF, EPSILON)
                    assertEquals(expected.height, key.heightF, EPSILON)
                }
            }
        }
    }

    @Test
    fun `Loader 为九宫格生成候选占位主区右列与独立底行几何`() {
        KeyboardLoaderUtil.instance.clearKeyboardMap()
        val keyboard = KeyboardLoaderUtil.instance.getSoftKeyboard(InputModeSwitcher.MASK_SKB_LAYOUT_T9_PINYIN)
        val rows = keyboard.mKeyRows

        assertEquals(listOf(5, 4, 4, 5), rows.map { it.size })
        rows.first().first().let { holder ->
            assertEquals(SogouT9Layout.candidateCodeView.x, holder.mLeftF, EPSILON)
            assertEquals(SogouT9Layout.candidateCodeView.y, holder.mTopF, EPSILON)
            assertEquals(SogouT9Layout.candidateCodeView.width, holder.widthF, EPSILON)
            assertEquals(SogouT9Layout.candidateCodeView.height, holder.heightF, EPSILON)
        }
        val expectedRows = SogouT9Layout.rowGeometry
        rows.forEachIndexed { rowIndex, row ->
            val actualKeys = if (rowIndex == 0) row.drop(1) else row
            actualKeys.forEachIndexed { keyIndex, key ->
                val expected = expectedRows[rowIndex].keys[keyIndex]
                assertEquals(expected.left, key.mLeftF, EPSILON)
                assertEquals(expected.top, key.mTopF, EPSILON)
                assertEquals(expected.width, key.widthF, EPSILON)
                assertEquals(expected.height, key.heightF, EPSILON)
            }
        }
    }

    @Test
    fun `Q 与 W 的视觉间隙按中线连续映射`() {
        val keyboard = load(InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_PINYIN)
        val q = SogouQwertyLayout.rowGeometry[0].keys[0]
        val w = SogouQwertyLayout.rowGeometry[0].keys[1]
        val y = q.top + q.height / 2f

        assertSame(keyboard.mKeyRows[0][0], keyboard.keyAt((q.right + q.touchRight) / 2f, y))
        assertSame(keyboard.mKeyRows[0][1], keyboard.keyAt((w.touchLeft + w.left) / 2f, y))
    }

    @Test
    fun `全键行间隙按行中线归属且不会跨行吸附`() {
        val keyboard = load(InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_PINYIN)
        val upper = SogouQwertyLayout.rowGeometry[0]
        val lower = SogouQwertyLayout.rowGeometry[1]
        val visualGapTop = upper.keys.first().bottom
        val visualGapBottom = lower.keys.first().top

        assertSame(
            keyboard.mKeyRows[0][0],
            keyboard.keyAt(0.05f, (visualGapTop + upper.touchBottom) / 2f),
        )
        assertSame(
            keyboard.mKeyRows[1][0],
            keyboard.keyAt(0.05f, (lower.touchTop + visualGapBottom) / 2f),
        )
    }

    @Test
    fun `九宫格列边界与行边界连续映射`() {
        val keyboard = load(InputModeSwitcher.MASK_SKB_LAYOUT_T9_PINYIN)
        val firstRow = SogouT9Layout.rowGeometry[0]
        val secondRow = SogouT9Layout.rowGeometry[1]
        val firstMain = firstRow.keys[0]
        val secondMain = firstRow.keys[1]
        val xBoundary = firstMain.touchRight

        assertSame(keyboard.mKeyRows[0][1], keyboard.keyAt(xBoundary - 0.0001f, 0.12f))
        assertSame(keyboard.mKeyRows[0][2], keyboard.keyAt(xBoundary + 0.0001f, 0.12f))
        assertSame(
            keyboard.mKeyRows[0][1],
            keyboard.keyAt(firstMain.left + firstMain.width / 2f, firstRow.touchBottom - 0.0001f),
        )
        assertSame(
            keyboard.mKeyRows[1][0],
            keyboard.keyAt(firstMain.left + firstMain.width / 2f, secondRow.touchTop + 0.0001f),
        )
    }

    @Test
    fun `有效首尾边缘归属边缘键而键盘外返回 null`() {
        val keyboard = load(InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_PINYIN)

        assertSame(keyboard.mKeyRows.first().first(), keyboard.mapToKey(0, 0))
        assertSame(keyboard.mKeyRows.last().last(), keyboard.mapToKey(TEST_WIDTH - 1, TEST_HEIGHT - 1))
        assertNull(keyboard.mapToKey(-1, TEST_HEIGHT / 2))
        assertNull(keyboard.mapToKey(TEST_WIDTH, TEST_HEIGHT / 2))
        assertNull(keyboard.mapToKey(TEST_WIDTH / 2, -1))
        assertNull(keyboard.mapToKey(TEST_WIDTH / 2, TEST_HEIGHT))
    }

    @Test
    fun `九宫格左候选占位覆盖主区三行且绝不映射字母`() {
        val keyboard = load(InputModeSwitcher.MASK_SKB_LAYOUT_T9_PINYIN)
        val holder = keyboard.mKeyRows.first().first()

        assertEquals(InputModeSwitcher.USER_KEYCODE_LEFT_SYMBOL, holder.code)
        assertSame(holder, keyboard.keyAt(0f, 0.12f))
        assertSame(holder, keyboard.keyAt(0.1f, 0.40f))
        assertSame(holder, keyboard.keyAt(0.1f, 0.70f))
        assertSame(keyboard.mKeyRows.last().first(), keyboard.keyAt(0f, 0.90f))
    }

    @Test
    fun `九宫格右列与底行首尾边缘保持正确键码`() {
        val keyboard = load(InputModeSwitcher.MASK_SKB_LAYOUT_T9_PINYIN)

        assertEquals(KeyEvent.KEYCODE_DEL, keyboard.keyAt(1f - 0.0001f, 0.12f)?.code)
        assertEquals(KeyEvent.KEYCODE_0, keyboard.keyAt(1f - 0.0001f, 0.70f)?.code)
        assertEquals(KeyEvent.KEYCODE_ENTER, keyboard.mapToKey(TEST_WIDTH - 1, TEST_HEIGHT - 1)?.code)
    }

    @Test
    fun `数字行开启后保持原有五比六缩放并下移官方几何`() {
        AppPrefs.getInstance().keyboardSetting.abcNumberLine.setValue(true)
        val keyboard = load(InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_PINYIN)
        val numberKey = keyboard.mKeyRows[0][0]
        val qKey = keyboard.mKeyRows[1][0]
        val qGeometry = SogouQwertyLayout.rowGeometry[0].keys[0]

        assertEquals(0f, numberKey.mTopF, EPSILON)
        assertEquals(0.2f / 1.2f, numberKey.heightF, EPSILON)
        assertEquals((0.2f + qGeometry.top) / 1.2f, qKey.mTopF, EPSILON)
        assertEquals(qGeometry.height / 1.2f, qKey.heightF, EPSILON)
        assertSame(
            qKey,
            keyboard.keyAt(0f, (0.2f + qGeometry.touchTop + 0.001f) / 1.2f),
        )
    }

    @Test
    fun `未配置搜狗命中图的其他布局维持视觉核心命中`() {
        val key = SoftKey(KeyEvent.KEYCODE_A).apply {
            setKeyDimensions(0.2f, 0.2f)
            widthF = 0.2f
            heightF = 0.2f
            setSkbCoreSize(TEST_WIDTH, TEST_HEIGHT)
        }
        val keyboard = SoftKeyboard(listOf(listOf(key)))

        assertSame(key, keyboard.keyAt(0.3f, 0.3f))
        assertNull(keyboard.keyAt(0.1f, 0.3f))
    }

    companion object {
        private const val EPSILON = 0.000001f
        private const val TEST_WIDTH = 10_000
        private const val TEST_HEIGHT = 10_000

        private fun load(layout: Int): SoftKeyboard {
            KeyboardLoaderUtil.instance.clearKeyboardMap()
            return KeyboardLoaderUtil.instance.getSoftKeyboard(layout)
        }

        private fun SoftKeyboard.keyAt(x: Float, y: Float) = mapToKey(
            x = (x * TEST_WIDTH).toInt(),
            y = (y * TEST_HEIGHT).toInt(),
        )

        private fun setEnvironmentDimension(fieldName: String, value: Int) {
            EnvironmentSingleton::class.java.getDeclaredField(fieldName).apply {
                isAccessible = true
                setInt(EnvironmentSingleton.instance, value)
            }
        }

    }
}
