package com.yuyan.imemodule.keyboard

import android.content.Context
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.emoji2.text.EmojiCompat
import com.yuyan.imemodule.adapter.SymbolAdapter
import com.yuyan.imemodule.adapter.SymbolPagerAdapter
import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.prefs.behavior.SymbolMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SymbolGridInteractionTest {
    private lateinit var context: Context

    @Before
    fun initializePreferences() {
        context = RuntimeEnvironment.getApplication()
        Launcher::class.java.getDeclaredField("context").apply {
            isAccessible = true
            set(Launcher.instance, context)
        }
        val preferences = context.getSharedPreferences("symbol-grid-interaction-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        AppPrefs.init(preferences)
        ThemeManager.init(context.resources.configuration)
        EmojiCompat.init(object : EmojiCompat.Config(EmojiCompat.MetadataRepoLoader { }) {})
    }

    @Test
    fun `符号网格规格提供稳定列数间距和最小触摸目标`() {
        assertEquals(6, SymbolGridSpec.COLUMN_COUNT)
        assertEquals(4, SymbolGridSpec.ITEM_GAP_DP)
        assertEquals(48, SymbolGridSpec.MIN_TOUCH_TARGET_DP)
    }

    @Test
    fun `中文英文符号共用稳定六列网格且整项可点击`() {
        var clicked: String? = null
        val pagerAdapter = SymbolPagerAdapter(
            context,
            linkedMapOf(0 to listOf("，", ",", "。", ".", "？", "?")),
            SymbolMode.Symbol,
        ) { symbol, _ -> clicked = symbol }
        val parent = FrameLayout(context)
        val pageHolder = pagerAdapter.onCreateViewHolder(parent, 0)
        pagerAdapter.onBindViewHolder(pageHolder, 0)

        val manager = pageHolder.emojiGroupRv.layoutManager
        assertTrue(manager is GridLayoutManager)
        assertEquals(6, (manager as GridLayoutManager).spanCount)

        val itemAdapter = pageHolder.emojiGroupRv.adapter as SymbolAdapter
        val itemHolder = itemAdapter.onCreateViewHolder(pageHolder.emojiGroupRv, 0)
        itemAdapter.onBindViewHolder(itemHolder, 0)

        val minimumTouchTarget = (48 * context.resources.displayMetrics.density).toInt()
        assertTrue(itemHolder.itemView.minimumHeight >= minimumTouchTarget)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, itemHolder.itemView.layoutParams.width)
        itemHolder.itemView.performClick()
        assertEquals("，", clicked)
    }
}
