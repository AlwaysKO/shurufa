package com.yuyan.imemodule.adapter

import android.app.Activity
import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.R
import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.data.KeyboardToolbarVisualItem
import com.yuyan.imemodule.data.menuSkbFunsPreset
import com.yuyan.imemodule.data.mergeKeyboardToolbarVisualItems
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

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
        adapter.items = listOf(KeyboardToolbarVisualItem("placeholder:test", null))
        var clicks = 0
        adapter.setOnItemClickLitener { _, _, _ -> clicks += 1 }
        val holder = adapter.onCreateViewHolder(FrameLayout(context), KeyboardToolbarModel.PLACEHOLDER_VIEW_TYPE)

        adapter.bindViewHolder(holder, 0)
        holder.itemView.performClick()

        val icon = holder.itemView.findViewById<ImageView>(R.id.candidates_menu_item)
        assertTrue(holder.itemView.minimumWidth >= dp(44))
        assertEquals(View.INVISIBLE, icon.visibility)
        assertFalse(holder.itemView.isClickable)
        assertEquals(0, clicks)
    }

    @Test
    fun `真实工具项具备无障碍说明主题着色和生产点击路由`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        activity.setContentView(root)
        val adapter = CandidatesMenuAdapter(activity)
        val item = requireNotNull(menuSkbFunsPreset[SkbMenuMode.AiDoutu])
        adapter.items = listOf(KeyboardToolbarVisualItem("fixed:ai_doutu", item))
        var clickedMode: SkbMenuMode? = null
        adapter.setOnItemClickLitener { source, _, position ->
            clickedMode = (source as CandidatesMenuAdapter).getMenuMode(position)
        }
        val recycler = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false)
            this.adapter = adapter
        }
        root.addView(recycler)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(dp(100), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp(44), View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        Shadows.shadowOf(activity.mainLooper).idle()
        val holder = requireNotNull(recycler.findViewHolderForAdapterPosition(0)) as CandidatesMenuAdapter.SymbolHolder

        holder.itemView.performClick()

        val icon = holder.itemView.findViewById<ImageView>(R.id.candidates_menu_item)
        assertTrue(holder.itemView.minimumWidth >= dp(44))
        assertTrue(holder.itemView.minimumHeight >= dp(44))
        assertEquals(item.funName, icon.contentDescription)
        assertEquals(ThemeManager.activeTheme.keyTextColor, icon.imageTintList?.defaultColor)
        assertNotNull(holder.itemView.background)
        assertEquals(SkbMenuMode.AiDoutu, clickedMode)
    }

    @Test
    fun `Diff移除期间迟到点击NO_POSITION不会回退旧下标触发动作`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        activity.setContentView(root)
        val adapter = CandidatesMenuAdapter(activity)
        val item = requireNotNull(menuSkbFunsPreset[SkbMenuMode.AiDoutu])
        adapter.items = listOf(KeyboardToolbarVisualItem("fixed:ai_doutu", item))
        var clicks = 0
        adapter.setOnItemClickLitener { _, _, _ -> clicks += 1 }
        val recycler = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false)
            this.adapter = adapter
        }
        root.addView(recycler)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(dp(100), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp(44), View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        Shadows.shadowOf(activity.mainLooper).idle()
        val lateHolder = requireNotNull(recycler.findViewHolderForAdapterPosition(0))
        val lateView = lateHolder.itemView

        adapter.items = emptyList()
        Shadows.shadowOf(activity.mainLooper).idle()
        lateView.performClick()

        assertEquals(RecyclerView.NO_POSITION, lateHolder.bindingAdapterPosition)
        assertEquals(0, clicks)
    }

    @Test
    fun `固定槽两个占位和重复动作都有稳定唯一identity`() {
        val voice = requireNotNull(menuSkbFunsPreset[SkbMenuMode.Voice])
        val empty = mergeKeyboardToolbarVisualItems(emptyList())
        val first = mergeKeyboardToolbarVisualItems(listOf(voice, voice))
        val second = mergeKeyboardToolbarVisualItems(listOf(voice, voice))

        assertEquals("fixed:emojicon", empty.first().slotId)
        assertEquals("fixed:quick_keyboard", empty[1].slotId)
        assertEquals("fixed:ai_doutu", empty.first { it.item?.skbMenuMode == SkbMenuMode.AiDoutu }.slotId)
        assertEquals(2, empty.count { it.item == null })
        assertEquals(empty.size, empty.map(KeyboardToolbarVisualItem::slotId).distinct().size)
        assertEquals(first.size, first.map(KeyboardToolbarVisualItem::slotId).distinct().size)
        assertEquals(2, first.count { it.item?.skbMenuMode == SkbMenuMode.Voice })
        assertEquals(first.map(KeyboardToolbarVisualItem::slotId), second.map(KeyboardToolbarVisualItem::slotId))
        val diff = CandidatesMenuAdapter.MyDiffCallback(first, second)
        first.indices.forEach { index -> assertTrue(diff.areItemsTheSame(index, index)) }
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
