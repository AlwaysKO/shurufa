package com.yuyan.imemodule.keyboard

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.application.CustomConstant
import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.manager.InputModeSwitcher
import com.yuyan.imemodule.prefs.AppPrefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ImeQuickKeyboardSettingsActionsTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Launcher::class.java.getDeclaredField("context").apply {
            isAccessible = true
            set(Launcher.instance, context)
        }
        AppPrefs.init(PreferenceManager.getDefaultSharedPreferences(context))
        InputModeSwitcher.reset()
    }

    @After
    fun tearDown() {
        AppPrefs.getInstance().internal.inputDefaultMode.setValue(InputModeSwitcher.MODE_T9_CHINESE)
        AppPrefs.getInstance().internal.inputMethodPinyinMode.setValue(InputModeSwitcher.MODE_T9_CHINESE)
        AppPrefs.getInstance().internal.pinyinModeRime.setValue(CustomConstant.SCHEMA_ZH_T9)
        InputModeSwitcher.reset()
    }

    @Test
    fun `真实英文模式链产生0x4020并选择english方案`() {
        var schema: String? = null

        InputModeSwitcher.switchToEnglishForSetting { schema = it }

        assertEquals(0x4000, InputModeSwitcher.skbLayout)
        assertTrue(InputModeSwitcher.isEnglish)
        assertEquals(0x4020, AppPrefs.getInstance().internal.inputDefaultMode.getValue())
        assertEquals(CustomConstant.SCHEMA_EN, schema)
    }

    @Test
    fun `真实临时键动作切到数字和文本编辑且返回不覆盖最近英文布局`() {
        val schemas = mutableListOf<String>()
        InputModeSwitcher.switchToEnglishForSetting { schemas += it }

        InputModeSwitcher.switchModeForUserKey(InputModeSwitcher.USER_KEYCODE_NUMBER) { schemas += it }
        assertEquals(InputModeSwitcher.MASK_SKB_LAYOUT_NUMBER, InputModeSwitcher.skbLayout)
        assertFalse(InputModeSwitcher.isEnglish)

        InputModeSwitcher.switchModeForUserKey(InputModeSwitcher.USER_KEYCODE_TEXTEDIT) { schemas += it }
        assertEquals(InputModeSwitcher.MASK_SKB_LAYOUT_TEXTEDIT, InputModeSwitcher.skbLayout)

        InputModeSwitcher.switchModeForUserKey(InputModeSwitcher.USER_KEYCODE_RETURN) { schemas += it }
        assertEquals(InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_ABC, InputModeSwitcher.skbLayout)
        assertTrue(InputModeSwitcher.isEnglish)
    }

    @Test
    fun `真实快捷动作分派覆盖英文临时键符号和主题关闭刷新`() {
        val runtime = RecordingImeQuickSettingsRuntime()
        val actions = ImeQuickKeyboardSettingsActions(runtime)

        actions.applyLayout(QuickKeyboardAction.EnglishQwerty)
        actions.applyLayout(QuickKeyboardAction.UserKey(InputModeSwitcher.USER_KEYCODE_NUMBER))
        actions.applyLayout(QuickKeyboardAction.UserKey(InputModeSwitcher.USER_KEYCODE_TEXTEDIT))
        actions.applyLayout(QuickKeyboardAction.Symbol(SymbolPage.CHINESE))
        actions.applyLayout(QuickKeyboardAction.Symbol(SymbolPage.ENGLISH))
        assertTrue(actions.applyTheme("MaterialDark"))
        actions.closeQuickSettings()

        assertEquals(
            listOf(
                "english",
                "key:${InputModeSwitcher.USER_KEYCODE_NUMBER}",
                "key:${InputModeSwitcher.USER_KEYCODE_TEXTEDIT}",
                "symbol:CHINESE",
                "symbol:ENGLISH",
                "theme:MaterialDark",
                "close:true",
            ),
            runtime.events,
        )
    }

    private class RecordingImeQuickSettingsRuntime : ImeQuickKeyboardSettingsRuntime {
        override val availableThemeIds = setOf("MaterialLight", "MaterialDark")
        override var currentThemeId = "MaterialLight"
        val events = mutableListOf<String>()
        private var themeChanged = false

        override fun switchChinese(layout: Int, schema: String) {
            events += "cn:$layout:$schema"
        }

        override fun switchEnglish() {
            events += "english"
        }

        override fun switchUserKey(keyCode: Int) {
            events += "key:$keyCode"
        }

        override fun showSymbol(page: SymbolPage) {
            events += "symbol:$page"
        }

        override fun applyTheme(themeId: String): Boolean {
            currentThemeId = themeId
            themeChanged = true
            events += "theme:$themeId"
            return true
        }

        override fun closeQuickSettings() {
            events += "close:$themeChanged"
            themeChanged = false
        }
    }
}
