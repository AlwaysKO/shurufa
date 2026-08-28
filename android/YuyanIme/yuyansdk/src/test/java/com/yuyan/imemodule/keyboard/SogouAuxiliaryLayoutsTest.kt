package com.yuyan.imemodule.keyboard

import android.content.Context
import android.view.KeyEvent
import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.entity.keyboard.KeyType
import com.yuyan.imemodule.manager.InputModeSwitcher
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.singleton.EnvironmentSingleton
import com.yuyan.imemodule.utils.KeyboardLoaderUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SogouAuxiliaryLayoutsTest {
    @Before
    fun initializePreferences() {
        val context = RuntimeEnvironment.getApplication()
        Launcher::class.java.getDeclaredField("context").apply {
            isAccessible = true
            set(Launcher.instance, context)
        }
        val preferences = context.getSharedPreferences("sogou-auxiliary-layout-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        AppPrefs.init(preferences)
        ThemeManager.init(context.resources.configuration)
        AppPrefs.getInstance().keyboardSetting.abcNumberLine.setValue(false)
        setEnvironmentDimension("skbWidth", TEST_WIDTH)
        setEnvironmentDimension("skbHeight", TEST_HEIGHT)
    }

    @Test
    fun `笔画与数字沿用九键官方四行几何而键码语义独立`() {
        val stroke = SogouAuxiliaryLayouts.stroke
        val number = SogouAuxiliaryLayouts.number

        assertEquals(listOf(5, 4, 4, 5), stroke.codeRows.map { it.size })
        assertEquals(listOf(5, 4, 4, 5), number.codeRows.map { it.size })
        assertEquals(SogouT9Layout.visualRowTops, stroke.visualRows.map { it.first().top })
        assertEquals(SogouT9Layout.visualRowTops, number.visualRows.map { it.first().top })
        assertEquals(KeyEvent.KEYCODE_H, stroke.codeRows[0][1])
        assertEquals(KeyEvent.KEYCODE_1, number.codeRows[0][1])
        assertEquals(KeyEvent.KEYCODE_0, number.codeRows.last()[2])
        assertEquals(
            listOf(0.1694f, 0.2167f, 0.2167f, 0.2167f, 0.1694f),
            number.visualRows.last().map { it.width },
        )
        assertNotEquals(stroke.visualRows.last().map { it.width }, number.visualRows.last().map { it.width })
    }

    @Test
    fun `手写规格保留大片书写区右侧符号列和独立底栏`() {
        val layout = SogouAuxiliaryLayouts.handwriting

        assertEquals(0f, layout.drawingArea.left, EPSILON)
        assertEquals(0.0046f, layout.drawingArea.top, EPSILON)
        assertEquals(0.8417f, layout.drawingArea.width, EPSILON)
        assertEquals(0.81585f, layout.drawingArea.height, EPSILON)
        assertEquals(listOf(1, 1, 5), layout.codeRows.map { it.size })
        assertEquals(0.1818f, layout.visualRows[1].single().top, EPSILON)
        assertEquals(0.634f, layout.visualRows[1].single().height, EPSILON)
        assertEquals(0.817f, layout.visualRows.last().first().top, EPSILON)
        assertEquals(0.9872f, layout.visualRows.last().last().bottom, EPSILON)
    }

    @Test
    fun `文字编辑采用纵向方向区和三段底栏而非统一四等分`() {
        val layout = SogouAuxiliaryLayouts.textEdit

        assertEquals(listOf(4, 2, 2, 3), layout.codeRows.map { it.size })
        assertEquals(0.74f, layout.visualRows.first()[0].height, EPSILON)
        assertEquals(0.24f, layout.visualRows.first()[1].height, EPSILON)
        assertEquals(0.255f, layout.visualRows[1][0].left, EPSILON)
        assertEquals(0.755f, layout.visualRows[1][1].left, EPSILON)
        assertEquals(0.3267f, layout.visualRows.last().first().width, EPSILON)
        assertTrue(layout.visualRows.flatten().all { it.left >= 0f && it.top >= 0f && it.right <= 1f && it.bottom <= 1f })
    }

    @Test
    fun `Loader 对所有辅助布局逐键应用对应视觉规格`() {
        listOf(
            InputModeSwitcher.MASK_SKB_LAYOUT_STROKE to SogouAuxiliaryLayouts.stroke,
            InputModeSwitcher.MASK_SKB_LAYOUT_NUMBER to SogouAuxiliaryLayouts.number,
            InputModeSwitcher.MASK_SKB_LAYOUT_HANDWRITING to SogouAuxiliaryLayouts.handwriting,
            InputModeSwitcher.MASK_SKB_LAYOUT_TEXTEDIT to SogouAuxiliaryLayouts.textEdit,
        ).forEach { (layoutCode, spec) ->
            KeyboardLoaderUtil.instance.clearKeyboardMap()
            val keyboard = KeyboardLoaderUtil.instance.getSoftKeyboard(layoutCode)

            assertEquals(spec.codeRows.map { it.size }, keyboard.mKeyRows.map { it.size })
            keyboard.mKeyRows.forEachIndexed { rowIndex, row ->
                row.forEachIndexed { keyIndex, key ->
                    val expected = spec.visualRows[rowIndex][keyIndex]
                    assertEquals(expected.left, key.mLeftF, EPSILON)
                    assertEquals(expected.top, key.mTopF, EPSILON)
                    assertEquals(expected.width, key.widthF, EPSILON)
                    assertEquals(expected.height, key.heightF, EPSILON)
                    assertEquals(spec.codeRows[rowIndex][keyIndex], key.code)
                }
            }
        }
    }

    @Test
    fun `辅助布局连续触摸边界与手写区互不抢占`() {
        val number = load(InputModeSwitcher.MASK_SKB_LAYOUT_NUMBER)
        assertEquals(KeyEvent.KEYCODE_1, number.keyAt(0.18f, 0.12f)?.code)
        assertEquals(KeyEvent.KEYCODE_2, number.keyAt(0.40f, 0.12f)?.code)
        assertEquals(KeyEvent.KEYCODE_DEL, number.keyAt(0.999f, 0.12f)?.code)
        assertEquals(InputModeSwitcher.USER_KEYCODE_RETURN, number.keyAt(0.36f, 0.88f)?.code)

        val stroke = load(InputModeSwitcher.MASK_SKB_LAYOUT_STROKE)
        assertEquals(KeyEvent.KEYCODE_SPACE, stroke.keyAt(0.36f, 0.88f)?.code)

        val handwriting = load(InputModeSwitcher.MASK_SKB_LAYOUT_HANDWRITING)
        assertEquals(null, handwriting.keyAt(0.40f, 0.40f))
        assertEquals(KeyEvent.KEYCODE_DEL, handwriting.keyAt(0.90f, 0.08f)?.code)
        assertEquals(InputModeSwitcher.USER_KEYCODE_LEFT_SYMBOL, handwriting.keyAt(0.90f, 0.40f)?.code)
    }

    @Test
    fun `九键文字主次层级和短功能标签清晰`() {
        assertEquals(arrayOf("abc", "2").toList(), KeyPreset.t9PYKeyPreset.getValue(KeyEvent.KEYCODE_A).toList())
        assertEquals(arrayOf("def", "3").toList(), KeyPreset.t9PYKeyPreset.getValue(KeyEvent.KEYCODE_D).toList())
        assertEquals("分词", KeyPreset.t9PYKeyPreset.getValue(KeyEvent.KEYCODE_APOSTROPHE).first())
        assertEquals("重输", KeyPreset.t9PYKeyPreset.getValue(KeyEvent.KEYCODE_CLEAR).first())
        assertEquals("符", KeyPreset.t9PYKeyPreset.getValue(InputModeSwitcher.USER_KEYCODE_SYMBOL).first())
        assertEquals("123", KeyPreset.t9PYKeyPreset.getValue(InputModeSwitcher.USER_KEYCODE_NUMBER).first())

        val key = load(InputModeSwitcher.MASK_SKB_LAYOUT_T9_PINYIN).getKeyByCode(KeyEvent.KEYCODE_A)!!
        assertTrue(key.mainLabelScale > key.secondaryLabelScale)
        assertTrue(key.secondaryLabelVerticalBias < key.mainLabelVerticalBias)
        assertEquals(KeyType.Normal, key.keyType)
    }

    @Test
    fun `功能键和回车键使用主题角色而非伪装普通键`() {
        val keyboard = load(InputModeSwitcher.MASK_SKB_LAYOUT_NUMBER)

        assertEquals(KeyType.Function, keyboard.getKeyByCode(KeyEvent.KEYCODE_DEL)?.keyType)
        assertEquals(KeyType.Function, keyboard.getKeyByCode(InputModeSwitcher.USER_KEYCODE_SYMBOL)?.keyType)
        assertEquals(KeyType.Function, keyboard.getKeyByCode(InputModeSwitcher.USER_KEYCODE_LEFT_PERIOD)?.keyType)
        assertEquals(KeyType.Function, keyboard.getKeyByCode(KeyEvent.KEYCODE_AT)?.keyType)
        assertEquals(KeyType.Function, keyboard.getKeyByCode(KeyEvent.KEYCODE_SPACE)?.keyType)
        assertEquals(KeyType.Normal, keyboard.getKeyByCode(KeyEvent.KEYCODE_0)?.keyType)
        assertEquals(KeyType.Normal, keyboard.getKeyByCode(KeyEvent.KEYCODE_5)?.keyType)
        assertEquals(KeyType.AccentKey, keyboard.getKeyByCode(KeyEvent.KEYCODE_ENTER)?.keyType)

        val textEdit = load(InputModeSwitcher.MASK_SKB_LAYOUT_TEXTEDIT)
        textEdit.mKeyRows.flatten().forEach { key ->
            assertEquals("文字编辑键 ${key.code}", KeyType.Function, key.keyType)
        }
    }

    companion object {
        private const val EPSILON = 0.00001f
        private const val TEST_WIDTH = 10_000
        private const val TEST_HEIGHT = 10_000

        private fun load(layout: Int) = KeyboardLoaderUtil.instance.run {
            clearKeyboardMap()
            getSoftKeyboard(layout)
        }

        private fun com.yuyan.imemodule.entity.keyboard.SoftKeyboard.keyAt(x: Float, y: Float) = mapToKey(
            (x * TEST_WIDTH).toInt(),
            (y * TEST_HEIGHT).toInt(),
        )

        private fun setEnvironmentDimension(fieldName: String, value: Int) {
            EnvironmentSingleton::class.java.getDeclaredField(fieldName).apply {
                isAccessible = true
                setInt(EnvironmentSingleton.instance, value)
            }
        }
    }
}
