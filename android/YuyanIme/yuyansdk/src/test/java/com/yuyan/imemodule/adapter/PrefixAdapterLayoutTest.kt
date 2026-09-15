package com.yuyan.imemodule.adapter

import android.content.Context
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.data.theme.ThemePreset
import com.yuyan.imemodule.prefs.AppPrefs
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PrefixAdapterLayoutTest {
    private val context = RuntimeEnvironment.getApplication()
    @Before fun setup() {
        Launcher::class.java.getDeclaredField("context").apply {
            isAccessible = true
            set(Launcher.instance, context)
        }
        val preferences = context.getSharedPreferences("side-grid-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        AppPrefs.init(preferences)
        // AppPrefs 只初始化一次；清理真正被 singleton 持有的 prefs，隔离各测试。
        val actual = org.robolectric.util.ReflectionHelpers.getField<android.content.SharedPreferences>(
            AppPrefs.getInstance(), "sharedPreferences",
        )
        actual.edit().clear().commit()
        ThemeManager.init(context.resources.configuration)
    }

    @Test fun `四个标点精确铺满侧栏且大字号不挤掉第四项`() {
        val adapter = PrefixAdapter(context, arrayOf("，", "。", "？", "！", "……"), viewportHeight = 403)
        val parent = RecyclerView(context).apply { layoutManager = LinearLayoutManager(context) }
        val heights = (0..4).map { position ->
            val holder = adapter.onCreateViewHolder(parent, 0)
            adapter.onBindViewHolder(holder, position)
            assertEquals(0, holder.tvSymbolType.paddingTop)
            assertEquals(0, holder.tvSymbolType.paddingBottom)
            holder.itemView.layoutParams.height
        }
        assertEquals(listOf(100, 101, 101, 101, 100), heights)
        assertEquals(403, heights.take(4).sum())
        assertEquals(5, adapter.itemCount) // 其余自定义符号仍可滚动，不能删除。
    }

    @Test fun `拼音使用更小字号并保留左右留白而标点字号不变`() {
        val width = com.yuyan.imemodule.singleton.EnvironmentSingleton.instance.skbWidth.takeIf { it > 0 }
            ?: context.resources.displayMetrics.widthPixels
        ThemeManager.prefs.keyboardFontSize.setValue(100)
        val parent = RecyclerView(context).apply { layoutManager = LinearLayoutManager(context) }
        val adapter = PrefixAdapter(context, arrayOf("chang", "biang", "，"), viewportHeight = 403)
        val holder = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(holder, 0)
        assertEquals(44f * width / 1080, holder.tvSymbolType.textSize, 0.01f)
        assertTrue(holder.tvSymbolType.paddingLeft > 0)
        assertEquals(holder.tvSymbolType.paddingLeft, holder.tvSymbolType.paddingRight)
        adapter.onBindViewHolder(holder, 1)
        assertEquals(44f * width / 1080, holder.tvSymbolType.textSize, 0.01f)
        adapter.onBindViewHolder(holder, 2)
        assertEquals(60f * width / 1080, holder.tvSymbolType.textSize, 0.01f)
        assertEquals(0, holder.tvSymbolType.paddingLeft)
    }

    @Test fun `其他候选栏未指定四格时继续按内容测量`() {
        val adapter = PrefixAdapter(context, arrayOf("chang"))
        val holder = adapter.onCreateViewHolder(RecyclerView(context).apply { layoutManager = LinearLayoutManager(context) }, 0)
        adapter.onBindViewHolder(holder, 0)
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, holder.itemView.layoutParams.height)
        assertTrue(holder.tvSymbolType.paddingTop > 0)
    }

    @Test fun `未选择主题的浅色键盘采用搜狗灰白默认色`() {
        ThemeManager.onSystemDarkModeChange(false)
        assertEquals("SogouDefault", ThemeManager.activeTheme.name)
        assertEquals(0xfff2f3f7.toInt(), ThemeManager.activeTheme.keyboardColor)
        assertEquals(0xffc5c9d3.toInt(), ThemeManager.activeTheme.functionKeyBackgroundColor)
        assertEquals(0xffffffff.toInt(), ThemeManager.activeTheme.keyBackgroundColor)
    }

    @Test fun `用户显式选过的旧主题不被覆盖`() {
        ThemeManager.prefs.followSystemDayNightTheme.setValue(false)
        ThemeManager.setNormalModeTheme(ThemePreset.MaterialLight)
        ThemeManager.init(context.resources.configuration)
        assertEquals("MaterialLight", ThemeManager.activeTheme.name)
    }
}
