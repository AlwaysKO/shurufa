package com.yuyan.imemodule.keyboard

import android.app.Application
import android.content.Context
import android.view.View
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.application.CustomConstant
import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.data.emojicon.YuyanEmojiCompat
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.data.theme.ThemePreset
import com.yuyan.imemodule.keyboard.container.SettingsContainer
import com.yuyan.imemodule.keyboard.container.SymbolContainer
import com.yuyan.imemodule.manager.InputModeSwitcher
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.prefs.behavior.SkbMenuMode
import com.yuyan.imemodule.prefs.behavior.SkbStyleMode
import com.yuyan.imemodule.prefs.behavior.SymbolMode
import com.yuyan.imemodule.service.ImeService
import com.yuyan.imemodule.singleton.EnvironmentSingleton
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class AndroidImeQuickKeyboardSettingsRuntimeTest {
    private lateinit var context: Context
    private val services = mutableListOf<ImeService>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Launcher::class.java.getDeclaredField("context").apply {
            isAccessible = true
            set(Launcher.instance, context)
        }
        AppPrefs.init(PreferenceManager.getDefaultSharedPreferences(context))
        ThemeManager.init(context.resources.configuration)
        YuyanEmojiCompat.init(context)
        EnvironmentSingleton.instance.initData(context)
        InputModeSwitcher.reset()
    }

    @After
    fun tearDown() {
        services.forEach(ImeService::onDestroy)
        services.clear()
        ThemeManager.prefs.followSystemDayNightTheme.setValue(false)
        ThemeManager.setNormalModeTheme(ThemePreset.MaterialLight)
        AppPrefs.getInstance().internal.inputDefaultMode.setValue(InputModeSwitcher.MODE_T9_CHINESE)
        AppPrefs.getInstance().internal.inputMethodPinyinMode.setValue(InputModeSwitcher.MODE_T9_CHINESE)
        AppPrefs.getInstance().internal.pinyinModeRime.setValue(CustomConstant.SCHEMA_ZH_T9)
        InputModeSwitcher.reset()
    }

    @Test
    fun `生产runtime驱动真实模式偏好符号ViewPager主题和刷新链`() {
        val inputView = realInputView()
        val symbolContainer = SymbolContainer(context, inputView)
        val initializedSchemas = mutableListOf<String>()
        val closeThemeFlags = mutableListOf<Boolean>()
        var themeRefreshes = 0
        val runtime = AndroidImeQuickKeyboardSettingsRuntime(
            symbolSurface = QuickSymbolSurface { page ->
                symbolContainer.setSymbolsView(if (page == SymbolPage.CHINESE) 1 else 2)
            },
            initializeSchema = { initializedSchemas += it },
            onThemeChanged = { themeRefreshes++ },
            onClose = { changed -> closeThemeFlags += changed },
        )

        runtime.switchChinese(InputModeSwitcher.MASK_SKB_LAYOUT_T9_PINYIN, CustomConstant.SCHEMA_ZH_T9)
        assertEquals(InputModeSwitcher.MASK_SKB_LAYOUT_T9_PINYIN, InputModeSwitcher.skbLayout)
        assertEquals(CustomConstant.SCHEMA_ZH_T9, AppPrefs.getInstance().internal.pinyinModeRime.getValue())
        assertEquals(CustomConstant.SCHEMA_ZH_T9, initializedSchemas.last())

        runtime.switchChinese(InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_PINYIN, CustomConstant.SCHEMA_ZH_QWERTY)
        assertEquals(InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_PINYIN, InputModeSwitcher.skbLayout)
        assertEquals(CustomConstant.SCHEMA_ZH_QWERTY, initializedSchemas.last())
        runtime.switchChinese(InputModeSwitcher.MASK_SKB_LAYOUT_HANDWRITING, CustomConstant.SCHEMA_ZH_HANDWRITING)
        assertEquals(InputModeSwitcher.MASK_SKB_LAYOUT_HANDWRITING, InputModeSwitcher.skbLayout)
        assertEquals(CustomConstant.SCHEMA_ZH_HANDWRITING, initializedSchemas.last())
        runtime.switchChinese(InputModeSwitcher.MASK_SKB_LAYOUT_STROKE, CustomConstant.SCHEMA_ZH_STROKE)
        assertEquals(InputModeSwitcher.MASK_SKB_LAYOUT_STROKE, InputModeSwitcher.skbLayout)
        assertEquals(CustomConstant.SCHEMA_ZH_STROKE, initializedSchemas.last())
        runtime.switchChinese(InputModeSwitcher.MASK_SKB_LAYOUT_LX17, CustomConstant.SCHEMA_ZH_DOUBLE_LX17)
        assertEquals(InputModeSwitcher.MASK_SKB_LAYOUT_LX17, InputModeSwitcher.skbLayout)
        assertEquals(CustomConstant.SCHEMA_ZH_DOUBLE_LX17, initializedSchemas.last())

        runtime.switchEnglish()
        assertEquals(0x4020, AppPrefs.getInstance().internal.inputDefaultMode.getValue())
        assertTrue(InputModeSwitcher.isEnglish)
        assertEquals(CustomConstant.SCHEMA_EN, initializedSchemas.last())

        runtime.switchUserKey(InputModeSwitcher.USER_KEYCODE_NUMBER)
        assertEquals(InputModeSwitcher.MASK_SKB_LAYOUT_NUMBER, InputModeSwitcher.skbLayout)
        runtime.switchUserKey(InputModeSwitcher.USER_KEYCODE_TEXTEDIT)
        assertEquals(InputModeSwitcher.MASK_SKB_LAYOUT_TEXTEDIT, InputModeSwitcher.skbLayout)
        runtime.switchUserKey(InputModeSwitcher.USER_KEYCODE_RETURN)
        assertEquals(InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_ABC, InputModeSwitcher.skbLayout)

        runtime.showSymbol(SymbolPage.CHINESE)
        assertEquals(SymbolPage.CHINESE, symbolContainer.quickSymbolPage)
        runtime.showSymbol(SymbolPage.ENGLISH)
        assertEquals(SymbolPage.ENGLISH, symbolContainer.quickSymbolPage)

        ThemeManager.prefs.followSystemDayNightTheme.setValue(true)
        ThemeManager.prefs.skbStyleMode.setValue(SkbStyleMode.Google)
        assertTrue(runtime.applyTheme("SogouBlue"))
        assertFalse(ThemeManager.prefs.followSystemDayNightTheme.getValue())
        assertEquals(SkbStyleMode.Yuyan, ThemeManager.prefs.skbStyleMode.getValue())
        assertEquals("SogouBlue", ThemeManager.activeTheme.name)
        assertEquals("SogouBlue", ThemeManager.prefs.normalModeTheme.getValue().name)
        assertEquals(1, themeRefreshes)
        runtime.closeQuickSettings()
        runtime.closeQuickSettings()
        assertEquals(listOf(true, false), closeThemeFlags)
    }

    @Test
    fun `生产SettingsMenuClick在真实InputView中显示切换和返回面板且不启动Activity`() {
        val inputView = realInputView()

        onSettingsMenuClick(inputView, SkbMenuMode.QuickKeyboard)
        val first = KeyboardManager.instance.currentContainer as SettingsContainer
        assertTrue(first.isQuickSettingsVisible)
        assertNull(shadowOf(context as Application).nextStartedActivity)

        onSettingsMenuClick(inputView, SkbMenuMode.QuickKeyboard)
        assertFalse(first.isQuickSettingsVisible)

        onSettingsMenuClick(inputView, SkbMenuMode.QuickKeyboard)
        assertTrue((KeyboardManager.instance.currentContainer as SettingsContainer).isQuickSettingsVisible)
        assertTrue(inputView.handleImePanelBack())
        assertFalse((KeyboardManager.instance.currentContainer as? SettingsContainer)?.isQuickSettingsVisible == true)
        assertNull(shadowOf(context as Application).nextStartedActivity)
    }

    @Test
    fun `生产面板使用runtime真实主题ID且符号入口定位真实页`() {
        val inputView = realInputView()
        ThemeManager.setNormalModeTheme(ThemePreset.PixelLight)
        onSettingsMenuClick(inputView, SkbMenuMode.QuickKeyboard)
        val settings = KeyboardManager.instance.currentContainer as SettingsContainer

        settings.findViewWithTag<View>("quick_tab_theme").performClick()
        KeyboardSurfaceThemes.options.forEach { option ->
            assertFalse(settings.findViewWithTag<View>("quick_theme_${option.themeId}").isSelected)
        }

        settings.findViewWithTag<View>("quick_tab_input").performClick()
        settings.findViewWithTag<View>("quick_layout_CHINESE_SYMBOL").performClick()
        val symbol = KeyboardManager.instance.currentContainer as SymbolContainer
        assertEquals(SymbolPage.CHINESE, symbol.quickSymbolPage)
        onSettingsMenuClick(inputView, SkbMenuMode.QuickKeyboard)
        val reopened = KeyboardManager.instance.currentContainer as SettingsContainer
        assertTrue(reopened.findViewWithTag<View>("quick_layout_CHINESE_SYMBOL").isSelected)

        ThemeManager.prefs.followSystemDayNightTheme.setValue(true)
        reopened.findViewWithTag<View>("quick_tab_theme").performClick()
        reopened.findViewWithTag<View>("quick_theme_WechatLayout").performClick()
        assertFalse(ThemeManager.prefs.followSystemDayNightTheme.getValue())
        assertEquals("WechatLayout", ThemeManager.activeTheme.name)
        assertEquals("WechatLayout", ThemeManager.prefs.normalModeTheme.getValue().name)
        assertTrue(reopened.findViewWithTag<View>("quick_theme_WechatLayout").isSelected)
        assertTrue(inputView.handleImePanelBack())
        assertFalse((KeyboardManager.instance.currentContainer as? SettingsContainer)?.isQuickSettingsVisible == true)
        assertNull(shadowOf(context as Application).nextStartedActivity)
    }


    @Test
    fun `从快捷面板切换表情会先关闭旧控制器再进入自有表情容器`() {
        val inputView = realInputView()
        inputView.onSettingsMenuClick(SkbMenuMode.QuickKeyboard)
        val oldSettings = KeyboardManager.instance.currentContainer as SettingsContainer
        assertTrue(oldSettings.isQuickSettingsVisible)

        inputView.onSettingsMenuClick(SkbMenuMode.Emojicon)

        assertFalse(oldSettings.isQuickSettingsVisible)
        val symbol = KeyboardManager.instance.currentContainer as SymbolContainer
        assertEquals(SymbolMode.Emojicon, symbol.getMenuMode())
        KeyboardManager.instance.switchKeyboard()
    }

    @Test
    fun `绕过菜单直接切换容器也会关闭快捷面板状态`() {
        val inputView = realInputView()
        inputView.onSettingsMenuClick(SkbMenuMode.QuickKeyboard)
        val oldSettings = KeyboardManager.instance.currentContainer as SettingsContainer
        assertTrue(oldSettings.isQuickSettingsVisible)

        KeyboardManager.instance.switchKeyboard(KeyboardManager.KeyboardType.ClipBoard)

        assertFalse(oldSettings.isQuickSettingsVisible)
        assertTrue(KeyboardManager.instance.currentContainer is com.yuyan.imemodule.keyboard.container.ClipBoardContainer)
    }

    private fun realInputView(): InputView {
        val service = Robolectric.buildService(ImeService::class.java).create().get()
        services += service
        return service.onCreateInputView() as InputView
    }
}
