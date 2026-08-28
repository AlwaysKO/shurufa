package com.yuyan.imemodule.keyboard

import com.yuyan.imemodule.application.CustomConstant
import com.yuyan.imemodule.manager.InputModeSwitcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickKeyboardSettingsModelTest {

    @Test
    fun `快捷面板只列出引擎真实支持的十个入口`() {
        assertEquals(
            listOf(
                QuickKeyboardLayoutId.CHINESE_T9,
                QuickKeyboardLayoutId.CHINESE_QWERTY,
                QuickKeyboardLayoutId.ENGLISH_QWERTY,
                QuickKeyboardLayoutId.HANDWRITING,
                QuickKeyboardLayoutId.STROKE,
                QuickKeyboardLayoutId.NUMBER,
                QuickKeyboardLayoutId.CHINESE_SYMBOL,
                QuickKeyboardLayoutId.ENGLISH_SYMBOL,
                QuickKeyboardLayoutId.TEXT_EDIT,
                QuickKeyboardLayoutId.LX17,
            ),
            QuickKeyboardSettingsModel.layouts.map { it.id },
        )
    }

    @Test
    fun `中文布局映射到现有布局和方案且英文与临时面板使用稳定动作`() {
        assertEquals(
            QuickKeyboardAction.ChineseMode(
                InputModeSwitcher.MASK_SKB_LAYOUT_T9_PINYIN,
                CustomConstant.SCHEMA_ZH_T9,
            ),
            actionOf(QuickKeyboardLayoutId.CHINESE_T9),
        )
        assertEquals(
            QuickKeyboardAction.ChineseMode(
                InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_PINYIN,
                CustomConstant.SCHEMA_ZH_QWERTY,
            ),
            actionOf(QuickKeyboardLayoutId.CHINESE_QWERTY),
        )
        assertEquals(QuickKeyboardAction.EnglishQwerty, actionOf(QuickKeyboardLayoutId.ENGLISH_QWERTY))
        assertEquals(
            QuickKeyboardAction.UserKey(InputModeSwitcher.USER_KEYCODE_NUMBER),
            actionOf(QuickKeyboardLayoutId.NUMBER),
        )
        assertEquals(
            QuickKeyboardAction.UserKey(InputModeSwitcher.USER_KEYCODE_TEXTEDIT),
            actionOf(QuickKeyboardLayoutId.TEXT_EDIT),
        )
        assertEquals(QuickKeyboardAction.Symbol(SymbolPage.CHINESE), actionOf(QuickKeyboardLayoutId.CHINESE_SYMBOL))
        assertEquals(QuickKeyboardAction.Symbol(SymbolPage.ENGLISH), actionOf(QuickKeyboardLayoutId.ENGLISH_SYMBOL))
    }

    @Test
    fun `主题只接受项目现有的浅色和深色主题`() {
        val themes = QuickKeyboardSettingsModel.availableThemes(
            setOf("MaterialDark", "Missing", "MaterialLight", "PixelLight"),
        )

        assertEquals(listOf("MaterialLight", "MaterialDark"), themes.map { it.themeId })
        assertTrue(QuickKeyboardSettingsModel.isThemeSelectable("MaterialLight", themes))
        assertTrue(QuickKeyboardSettingsModel.isThemeSelectable("MaterialDark", themes))
        assertFalse(QuickKeyboardSettingsModel.isThemeSelectable("PixelLight", themes))
        assertFalse(QuickKeyboardSettingsModel.isThemeSelectable("Missing", themes))
        assertFalse(QuickKeyboardSettingsModel.isThemeSelectable("", themes))
    }

    @Test
    fun `当前布局选中态区分中文英文和临时布局`() {
        assertEquals(
            QuickKeyboardLayoutId.ENGLISH_QWERTY,
            QuickKeyboardSettingsModel.selectedLayout(
                InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_ABC,
                isEnglish = true,
            ),
        )
        assertEquals(
            QuickKeyboardLayoutId.CHINESE_QWERTY,
            QuickKeyboardSettingsModel.selectedLayout(
                InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_PINYIN,
                isEnglish = false,
            ),
        )
        assertEquals(
            QuickKeyboardLayoutId.NUMBER,
            QuickKeyboardSettingsModel.selectedLayout(
                InputModeSwitcher.MASK_SKB_LAYOUT_NUMBER,
                isEnglish = false,
            ),
        )
        assertNull(QuickKeyboardSettingsModel.selectedLayout(0x9000, isEnglish = false))
    }

    @Test
    fun `控制器布局点击执行真实动作并关闭面板`() {
        val actions = RecordingQuickSettingsActions()
        val controller = QuickKeyboardSettingsController(actions)
        controller.show()

        assertTrue(controller.selectLayout(QuickKeyboardLayoutId.STROKE))

        assertEquals(actionOf(QuickKeyboardLayoutId.STROKE), actions.layoutAction)
        assertEquals(1, actions.closeCount)
        assertFalse(controller.isVisible)
    }

    @Test
    fun `控制器主题点击只允许有效主题并保持面板显示选中态`() {
        val actions = RecordingQuickSettingsActions()
        val controller = QuickKeyboardSettingsController(actions)
        controller.show()

        assertFalse(controller.selectTheme("PixelLight"))
        assertNull(actions.themeId)
        assertTrue(controller.selectTheme("MaterialDark"))
        assertEquals("MaterialDark", actions.themeId)
        assertEquals("MaterialDark", controller.selectedThemeId)
        assertTrue(controller.isVisible)
        assertEquals(0, actions.closeCount)
    }

    @Test
    fun `切换到普通设置内容只清除快捷面板状态而不误触发返回键盘`() {
        val actions = RecordingQuickSettingsActions()
        val controller = QuickKeyboardSettingsController(actions)
        controller.show()

        controller.dismiss()

        assertFalse(controller.isVisible)
        assertEquals(0, actions.closeCount)
        assertFalse(controller.handleBack())
    }

    @Test
    fun `再次点击和系统返回都关闭快捷面板并回到输入键盘`() {
        val actions = RecordingQuickSettingsActions()
        val controller = QuickKeyboardSettingsController(actions)

        assertTrue(controller.toggle())
        assertTrue(controller.isVisible)
        assertFalse(controller.toggle())
        assertFalse(controller.isVisible)
        assertEquals(1, actions.closeCount)

        controller.show()
        assertTrue(controller.handleBack())
        assertFalse(controller.isVisible)
        assertEquals(2, actions.closeCount)
        assertFalse(controller.handleBack())
        assertEquals(2, actions.closeCount)
    }

    private fun actionOf(id: QuickKeyboardLayoutId): QuickKeyboardAction =
        QuickKeyboardSettingsModel.layouts.single { it.id == id }.action

    private class RecordingQuickSettingsActions : QuickKeyboardSettingsActions {
        override val availableThemeIds = setOf("MaterialLight", "MaterialDark", "PixelLight")
        override var currentThemeId: String = "MaterialLight"
        var layoutAction: QuickKeyboardAction? = null
        var themeId: String? = null
        var closeCount = 0

        override fun applyLayout(action: QuickKeyboardAction) {
            layoutAction = action
        }

        override fun applyTheme(themeId: String): Boolean {
            this.themeId = themeId
            currentThemeId = themeId
            return true
        }

        override fun closeQuickSettings() {
            closeCount++
        }
    }
}
