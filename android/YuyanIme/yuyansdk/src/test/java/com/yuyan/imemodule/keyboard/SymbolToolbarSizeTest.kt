package com.yuyan.imemodule.keyboard

import android.content.Context
import android.view.View
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.viewpager2.widget.ViewPager2
import com.yuyan.imemodule.R
import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.data.emojicon.YuyanEmojiCompat
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.data.theme.ThemePreset
import com.yuyan.imemodule.keyboard.container.SettingsContainer
import com.yuyan.imemodule.keyboard.container.SymbolContainer
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.prefs.behavior.SkbMenuMode
import com.yuyan.imemodule.singleton.EnvironmentSingleton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class SymbolToolbarSizeTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Launcher::class.java.getDeclaredField("context").apply {
            isAccessible = true
            set(Launcher.instance, context)
        }
        AppPrefs.init(PreferenceManager.getDefaultSharedPreferences(context))
        YuyanEmojiCompat.init(context)
        ThemeManager.init(context.resources.configuration)
        EnvironmentSingleton.instance.initData(context)
        ThemeManager.prefs.followSystemDayNightTheme.setValue(false)
        ThemeManager.setNormalModeTheme(ThemePreset.SogouDefault)
    }

    @After
    fun tearDown() {
        ThemeManager.prefs.followSystemDayNightTheme.setValue(false)
        ThemeManager.setNormalModeTheme(ThemePreset.SogouDefault)
    }


    @Test
    fun `表情符号和颜文字底栏都有48dp点击区域和32dp图标`() {
        for (mode in com.yuyan.imemodule.prefs.behavior.SymbolMode.values()) {
            val container = SymbolContainer(context, unsafeInputView())
            if (mode == com.yuyan.imemodule.prefs.behavior.SymbolMode.Symbol) container.setSymbolsView()
            else container.setEmojisView(mode)
            val footer = container.findViewById<View>(R.id.ll_symbols_emoji_type_item)
            footer.measure(View.MeasureSpec.makeMeasureSpec(dp(360), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dp(100), View.MeasureSpec.AT_MOST))
            footer.layout(0, 0, footer.measuredWidth, footer.measuredHeight)
            assertEquals("底栏高度: $mode", dp(48), footer.measuredHeight)
            for (id in listOf(R.id.iv_symbols_emoji_type_return, R.id.iv_symbols_emoji_type_delete)) {
                val button = footer.findViewById<View>(id)
                assertTrue(button.measuredWidth >= dp(48))
                assertTrue(button.measuredHeight >= dp(48))
            }
            val tabs = footer.findViewById<com.google.android.material.tabs.TabLayout>(R.id.tab_symbols_emoji_type)
            assertEquals(com.google.android.material.tabs.TabLayout.MODE_SCROLLABLE, tabs.tabMode)
            for (i in 0 until tabs.tabCount) {
                val tab = tabs.getTabAt(i)!!
                assertTrue("分类宽度: $mode/$i", tab.view.measuredWidth >= dp(48))
                assertEquals(dp(32), tab.customView!!.measuredWidth)
                assertEquals(dp(32), tab.customView!!.measuredHeight)
            }
        }
    }
    @Test
    fun `表情内容区位于加高工具栏上方且不遮挡点击`() {
        val container = SymbolContainer(context, unsafeInputView())
        container.setSymbolsView()
        container.measure(View.MeasureSpec.makeMeasureSpec(dp(360), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp(280), View.MeasureSpec.EXACTLY))
        container.layout(0, 0, container.measuredWidth, container.measuredHeight)
        val footer = container.findViewById<View>(R.id.ll_symbols_emoji_type_item)
        val pager = SymbolContainer::class.java.getDeclaredField("mVPSymbolsView").run {
            isAccessible = true
            get(container) as ViewPager2
        }
        assertTrue("内容区必须有高度", pager.height > 0)
        assertEquals("内容区从顶部开始", 0, pager.top)
        assertTrue("内容区不能覆盖底栏: ${pager.bottom} > ${footer.top}", pager.bottom <= footer.top)
        assertEquals(dp(48), footer.height)
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

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
