package com.yuyan.imemodule.adapter

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.R
import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.data.menuSkbFunsPreset
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.keyboard.KeyboardToolbarModel
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.prefs.behavior.SkbMenuMode
import com.yuyan.imemodule.singleton.EnvironmentSingleton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CandidatesMenuAdapterTest {
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
    fun `占位槽保留44dp触摸宽度且不可点击`() {
        val adapter = CandidatesMenuAdapter(context)
        adapter.items = listOf(null)
        var clicks = 0
        adapter.setOnItemClickLitener { _, _, _ -> clicks += 1 }
        val holder = adapter.onCreateViewHolder(FrameLayout(context), KeyboardToolbarModel.PLACEHOLDER_VIEW_TYPE)

        adapter.onBindViewHolder(holder, 0)
        holder.itemView.performClick()

        val icon = holder.itemView.findViewById<ImageView>(R.id.candidates_menu_item)
        assertTrue(holder.itemView.minimumWidth >= dp(44))
        assertEquals(View.INVISIBLE, icon.visibility)
        assertFalse(holder.itemView.isClickable)
        assertEquals(0, clicks)
    }

    @Test
    fun `真实工具项具备无障碍说明主题着色和生产点击路由`() {
        val adapter = CandidatesMenuAdapter(context)
        val item = requireNotNull(menuSkbFunsPreset[SkbMenuMode.AiDoutu])
        adapter.items = listOf(item)
        var clickedMode: SkbMenuMode? = null
        adapter.setOnItemClickLitener { source, _, position ->
            clickedMode = (source as CandidatesMenuAdapter).getMenuMode(position)
        }
        val holder = adapter.onCreateViewHolder(FrameLayout(context), 0)

        adapter.onBindViewHolder(holder, 0)
        holder.itemView.performClick()

        val icon = holder.itemView.findViewById<ImageView>(R.id.candidates_menu_item)
        assertTrue(holder.itemView.minimumWidth >= dp(44))
        assertTrue(holder.itemView.minimumHeight >= dp(44))
        assertEquals(item.funName, icon.contentDescription)
        assertEquals(ThemeManager.activeTheme.keyTextColor, icon.imageTintList?.defaultColor)
        assertNotNull(holder.itemView.background)
        assertEquals(SkbMenuMode.AiDoutu, clickedMode)
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
