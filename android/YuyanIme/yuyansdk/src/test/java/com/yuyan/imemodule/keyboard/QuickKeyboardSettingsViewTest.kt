package com.yuyan.imemodule.keyboard

import android.content.Context
import android.view.View
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.keyboard.container.SettingsContainer
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.singleton.EnvironmentSingleton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class QuickKeyboardSettingsViewTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Launcher::class.java.getDeclaredField("context").apply {
            isAccessible = true
            set(Launcher.instance, context)
        }
        AppPrefs.init(PreferenceManager.getDefaultSharedPreferences(context))
        ThemeManager.init(context.resources.configuration)
        EnvironmentSingleton.instance.initData(context)
    }

    @Test
    fun `快捷面板在IME内显示十种布局和两个主题且触摸目标不小于44dp`() {
        val actions = RecordingActions()
        val container = SettingsContainer(context, unsafeInputView(), actions)

        container.showQuickSettingsView()

        QuickKeyboardSettingsModel.layouts.forEach { option ->
            val button = container.findViewWithTag<View>("quick_layout_${option.id.name}")
            assertTrue("缺少 ${option.id}", button != null)
            assertTrue(button.minimumHeight >= dp(44))
        }
        listOf("MaterialLight", "MaterialDark").forEach { id ->
            val button = container.findViewWithTag<View>("quick_theme_$id")
            assertTrue("缺少 $id", button != null)
            assertTrue(button.minimumHeight >= dp(44))
        }
        assertTrue(container.findViewWithTag<View>("quick_theme_MaterialLight").isSelected)
        assertFalse(container.findViewWithTag<View>("quick_theme_MaterialDark").isSelected)
        assertNull(shadowOf(context as android.app.Application).nextStartedActivity)
    }

    @Test
    fun `面板布局和主题点击走可注入真实控制路径且不启动Activity`() {
        val actions = RecordingActions()
        val container = SettingsContainer(context, unsafeInputView(), actions)
        container.showQuickSettingsView()

        container.findViewWithTag<View>("quick_theme_MaterialDark").performClick()
        assertEquals("MaterialDark", actions.themeId)
        assertTrue(container.findViewWithTag<View>("quick_theme_MaterialDark").isSelected)
        assertNull(shadowOf(context as android.app.Application).nextStartedActivity)

        container.findViewWithTag<View>("quick_layout_STROKE").performClick()
        assertEquals(
            QuickKeyboardSettingsModel.layouts.single { it.id == QuickKeyboardLayoutId.STROKE }.action,
            actions.layoutAction,
        )
        assertEquals(1, actions.closeCount)
        assertFalse(container.isQuickSettingsVisible)
        assertNull(shadowOf(context as android.app.Application).nextStartedActivity)
    }

    @Test
    fun `真实主题动作即时生效并关闭跟随系统后持久化且拒绝未知主题`() {
        ThemeManager.prefs.followSystemDayNightTheme.setValue(true)

        assertTrue(SettingsContainer.applyQuickTheme("MaterialDark"))
        assertFalse(ThemeManager.prefs.followSystemDayNightTheme.getValue())
        assertEquals("MaterialDark", ThemeManager.prefs.normalModeTheme.getValue().name)
        assertEquals("MaterialDark", ThemeManager.activeTheme.name)

        assertFalse(SettingsContainer.applyQuickTheme("NotInstalled"))
        assertEquals("MaterialDark", ThemeManager.activeTheme.name)
    }

    @Test
    fun `面板返回和再次切换恢复输入键盘`() {
        val actions = RecordingActions()
        val container = SettingsContainer(context, unsafeInputView(), actions)
        container.showQuickSettingsView()

        assertTrue(container.handleQuickSettingsBack())
        assertEquals(1, actions.closeCount)
        assertFalse(container.isQuickSettingsVisible)
        assertFalse(container.handleQuickSettingsBack())

        assertTrue(container.toggleQuickSettingsView())
        assertFalse(container.toggleQuickSettingsView())
        assertEquals(2, actions.closeCount)
    }

    private fun unsafeInputView(): InputView {
        val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").run {
            isAccessible = true
            get(null)
        }
        return unsafe.javaClass
            .getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, InputView::class.java) as InputView
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private class RecordingActions : QuickKeyboardSettingsActions {
        override val availableThemeIds = setOf("MaterialLight", "MaterialDark", "PixelLight")
        override var currentThemeId = "MaterialLight"
        var themeId: String? = null
        var layoutAction: QuickKeyboardAction? = null
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
