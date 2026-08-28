package com.yuyan.imemodule.expression.ui

import android.app.Activity
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.R
import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.data.theme.ThemePreset
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.expression.ExpressionCatalog
import com.yuyan.imemodule.expression.ExpressionPanelState
import com.yuyan.imemodule.expression.ExpressionPanelTab
import com.yuyan.imemodule.expression.model.ExpressionAsset
import com.yuyan.imemodule.expression.model.ExpressionCatalogDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExpressionPanelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    @Before
    fun setUp() {
        Launcher::class.java.getDeclaredField("context").apply {
            isAccessible = true
            set(Launcher.instance, context)
        }
        AppPrefs.init(PreferenceManager.getDefaultSharedPreferences(context))
        ThemeManager.init(context.resources.configuration)
        ThemeManager.setNormalModeTheme(ThemePreset.MaterialLight)
    }

    @After
    fun tearDown() {
        ThemeManager.setNormalModeTheme(ThemePreset.MaterialLight)
    }

    private val catalog = ExpressionCatalog(
        ExpressionCatalogDocument(
            version = "v1",
            templates = emptyList(),
            emojiBases = emptyList(),
            emojiCombinations = emptyList(),
        ),
    )

    @Test
    fun `紧凑态显示横向单行且推荐标签具有选中标记`() {
        val panel = ExpressionPanel(context)
        val state = visibleState()

        panel.render(state, catalog)

        val recommended = panel.findViewById<TextView>(R.id.expression_tab_recommended)
        val templates = panel.findViewById<TextView>(R.id.expression_tab_templates)
        val list = panel.findViewById<RecyclerView>(R.id.expression_asset_list)
        assertTrue(recommended.isSelected)
        assertFalse(templates.isSelected)
        assertNotNull(recommended.background)
        assertTrue(list.layoutManager is LinearLayoutManager)
        assertEquals(
            RecyclerView.HORIZONTAL,
            (list.layoutManager as LinearLayoutManager).orientation,
        )
    }

    @Test
    fun `展开态显示三列纵向网格且标签选中状态唯一`() {
        val panel = ExpressionPanel(context)
        val state = visibleState().apply {
            selectTab(ExpressionPanelTab.AI_SYNTHESIS)
            expand()
        }

        panel.render(state, catalog)

        val recommended = panel.findViewById<TextView>(R.id.expression_tab_recommended)
        val templates = panel.findViewById<TextView>(R.id.expression_tab_templates)
        val emoji = panel.findViewById<TextView>(R.id.expression_tab_emoji)
        val list = panel.findViewById<RecyclerView>(R.id.expression_asset_list)
        assertFalse(recommended.isSelected)
        assertTrue(templates.isSelected)
        assertFalse(emoji.isSelected)
        assertNotNull(templates.background)
        assertTrue(list.layoutManager is GridLayoutManager)
        assertEquals(3, (list.layoutManager as GridLayoutManager).spanCount)
    }

    @Test
    fun `开启时显示搜狗式标签和操作入口`() {
        val panel = ExpressionPanel(context)

        panel.render(visibleState(), catalog)

        assertEquals(View.VISIBLE, panel.findViewById<View>(R.id.expression_recommendation_section).visibility)
        assertEquals(View.VISIBLE, panel.findViewById<View>(R.id.expression_tool_row).visibility)
        assertEquals("推荐", panel.findViewById<TextView>(R.id.expression_tab_recommended).text.toString())
        assertEquals("AI合成", panel.findViewById<TextView>(R.id.expression_tab_templates).text.toString())
        assertEquals("Emoji合成", panel.findViewById<TextView>(R.id.expression_tab_emoji).text.toString())
        assertNotNull(panel.findViewById<View>(R.id.expression_actions).background)
        assertEquals(View.VISIBLE, panel.findViewById<View>(R.id.expression_more).visibility)
        assertEquals(View.VISIBLE, panel.findViewById<View>(R.id.expression_close).visibility)
        assertEquals("关闭AI斗图推荐", panel.findViewById<View>(R.id.expression_close).contentDescription)
        assertEquals(View.VISIBLE, panel.findViewById<View>(R.id.expression_enable).visibility)
    }

    @Test
    fun `关闭时隐藏推荐区并保留独立 AI斗图工具行`() {
        val panel = ExpressionPanel(context)
        val state = visibleState().apply { setAiStickerEnabled(false) }

        panel.render(state, catalog)

        assertEquals(View.VISIBLE, panel.visibility)
        assertEquals(View.GONE, panel.findViewById<View>(R.id.expression_recommendation_section).visibility)
        assertEquals(View.VISIBLE, panel.findViewById<View>(R.id.expression_tool_row).visibility)
        val enable = panel.findViewById<TextView>(R.id.expression_enable)
        assertEquals(View.VISIBLE, enable.visibility)
        assertEquals("AI斗图", enable.text.toString())
    }

    @Test
    fun `非聊天输入框也只显示独立工具行`() {
        val panel = ExpressionPanel(context)
        val state = visibleState().apply { setChatEditor(false) }

        panel.render(state, catalog)

        assertEquals(View.GONE, panel.findViewById<View>(R.id.expression_recommendation_section).visibility)
        assertEquals(View.VISIBLE, panel.findViewById<View>(R.id.expression_tool_row).visibility)
    }

    @Test
    fun `推荐区排列在独立工具行上方`() {
        val panel = ExpressionPanel(context)
        val recommendation = panel.findViewById<View>(R.id.expression_recommendation_section)
        val toolRow = panel.findViewById<View>(R.id.expression_tool_row)

        assertTrue(panel.indexOfChild(recommendation) < panel.indexOfChild(toolRow))
        assertTrue(panel.findViewById<View>(R.id.expression_actions) is LinearLayout)
    }

    @Test
    fun `圆环关闭后工具标签重新开启并等待下一次推荐`() {
        val panel = ExpressionPanel(context)
        val state = visibleState()
        panel.onAiStickerEnabledChange = { enabled ->
            state.setAiStickerEnabled(enabled)
            panel.render(state, catalog)
        }
        panel.render(state, catalog)

        panel.findViewById<View>(R.id.expression_close).performClick()
        assertEquals(View.GONE, panel.findViewById<View>(R.id.expression_recommendation_section).visibility)

        panel.findViewById<View>(R.id.expression_enable).performClick()
        assertEquals(View.GONE, panel.findViewById<View>(R.id.expression_recommendation_section).visibility)

        state.beginQuery("你好", 2)
        state.applyResults(2, listOf(asset()))
        panel.render(state, catalog)
        assertEquals(View.VISIBLE, panel.findViewById<View>(R.id.expression_recommendation_section).visibility)
    }

    @Test
    fun `AI 已开启时工具标签不重复触发开启回调`() {
        val panel = ExpressionPanel(context)
        var callbackCount = 0
        panel.onAiStickerEnabledChange = { callbackCount += 1 }
        panel.render(visibleState(), catalog)

        panel.findViewById<View>(R.id.expression_enable).performClick()

        assertEquals(0, callbackCount)
    }

    @Test
    fun `展开态素材项保持固定正方形`() {
        val adapter = ExpressionAssetAdapter {}
        val parent = RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, 3)
        }
        val holder = adapter.onCreateViewHolder(parent, 0)
        val metrics = ExpressionLayoutMetrics.calculate(1200, 3.25f, landscape = false)
        adapter.setLayoutMetrics(metrics)
        adapter.setExpanded(true)
        adapter.submitList(listOf(asset()))

        adapter.onBindViewHolder(holder, 0)

        assertTrue(holder.itemView.layoutParams.width > 0)
        assertEquals(holder.itemView.layoutParams.width, holder.itemView.layoutParams.height)
        assertEquals(metrics.expandedItemSizePx, holder.itemView.layoutParams.width)
    }

    @Test
    fun `标签图片区胶囊和工具行应用参考图尺寸`() {
        val panel = ExpressionPanel(context)
        panel.render(visibleState(), catalog)
        val density = context.resources.displayMetrics.density
        val metrics = ExpressionLayoutMetrics.calculate(
            context.resources.displayMetrics.widthPixels,
            density,
            landscape = false,
        )

        assertEquals(metrics.tabRowHeightPx, panel.findViewById<View>(R.id.expression_tab_bar).layoutParams.height)
        assertEquals(metrics.contentHeightPx, panel.findViewById<View>(R.id.expression_content).layoutParams.height)
        assertEquals(metrics.toolRowHeightPx, panel.findViewById<View>(R.id.expression_tool_row).layoutParams.height)
        assertEquals(metrics.actionWidthPx, panel.findViewById<View>(R.id.expression_actions).layoutParams.width)
        assertEquals(metrics.actionHeightPx, panel.findViewById<View>(R.id.expression_actions).layoutParams.height)
    }

    @Test
    fun `GIF 也优先使用缩略图预览`() {
        val gif = asset().copy(
            format = "gif",
            fileName = "templates/a.gif",
            thumbnailFileName = "thumbnails/a.webp",
        )

        assertEquals(
            "file:///android_asset/expression/thumbnails/a.webp",
            previewSource(gif),
        )
    }

    @Test
    fun `长按推荐列表请求展开`() {
        val panel = ExpressionPanel(context)
        var expansions = 0
        panel.onExpandRequested = { expansions += 1 }
        panel.render(visibleState(), catalog)

        panel.findViewById<RecyclerView>(R.id.expression_asset_list).performLongClick()

        assertEquals(1, expansions)
    }


    @Test
    fun `无结果时长按和上滑都不会请求展开`() {
        val panel = ExpressionPanel(context)
        var expansions = 0
        panel.onExpandRequested = { expansions += 1 }
        panel.render(ExpressionPanelState(), catalog)
        val list = panel.findViewById<RecyclerView>(R.id.expression_asset_list)

        list.performLongClick()
        swipe(list, fromX = 120f, fromY = 120f, toX = 118f, toY = 24f)

        assertEquals(0, expansions)
    }

    @Test
    fun `有结果时上滑展开而横滑结果不会误触展开`() {
        val panel = ExpressionPanel(context)
        var expansions = 0
        panel.onExpandRequested = { expansions += 1 }
        panel.render(visibleState(), catalog)
        val list = panel.findViewById<RecyclerView>(R.id.expression_asset_list)

        swipe(list, fromX = 180f, fromY = 100f, toX = 42f, toY = 88f)
        assertEquals(0, expansions)

        swipe(list, fromX = 120f, fromY = 120f, toX = 118f, toY = 24f)
        assertEquals(1, expansions)
    }

    @Test
    fun `关闭与恢复入口保留当前查询结果和标签`() {
        val panel = ExpressionPanel(context)
        val state = visibleState().apply { selectTab(ExpressionPanelTab.AI_SYNTHESIS) }
        panel.onRecommendationVisibilityChange = { visible ->
            if (visible) state.restoreRecommendations() else state.hideRecommendations()
            panel.render(state, catalog)
        }
        panel.render(state, catalog)

        panel.findViewById<View>(R.id.expression_close).performClick()
        assertEquals(View.GONE, panel.findViewById<View>(R.id.expression_recommendation_section).visibility)
        assertEquals("你好", state.query)
        assertEquals(1, state.results.size)
        assertEquals(ExpressionPanelTab.AI_SYNTHESIS, state.selectedTab)

        panel.findViewById<View>(R.id.expression_enable).performClick()
        assertEquals(View.VISIBLE, panel.findViewById<View>(R.id.expression_recommendation_section).visibility)
        assertEquals("你好", state.query)
        assertEquals(1, state.results.size)
        assertEquals(ExpressionPanelTab.AI_SYNTHESIS, state.selectedTab)
    }

    @Test
    fun `浅深主题渲染都使用当前主题颜色而非固定浅色`() {
        com.yuyan.imemodule.data.theme.ThemeManager.setNormalModeTheme(
            com.yuyan.imemodule.data.theme.ThemePreset.MaterialLight,
        )
        val panel = ExpressionPanel(context)
        panel.render(visibleState(), catalog)
        val lightToolColor = (panel.findViewById<View>(R.id.expression_tool_row).background as android.graphics.drawable.ColorDrawable).color
        val lightTextColor = panel.findViewById<TextView>(R.id.expression_enable).currentTextColor

        com.yuyan.imemodule.data.theme.ThemeManager.setNormalModeTheme(
            com.yuyan.imemodule.data.theme.ThemePreset.MaterialDark,
        )
        panel.render(visibleState(), catalog)
        val darkToolColor = (panel.findViewById<View>(R.id.expression_tool_row).background as android.graphics.drawable.ColorDrawable).color
        val darkTextColor = panel.findViewById<TextView>(R.id.expression_enable).currentTextColor

        assertNotEquals(lightToolColor, darkToolColor)
        assertNotEquals(lightTextColor, darkTextColor)
    }


    @Test
    fun `重复挂载卸载不会叠加展开监听`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        activity.setContentView(root)
        val panel = ExpressionPanel(activity)
        var expansions = 0
        panel.onExpandRequested = { expansions += 1 }
        panel.render(visibleState(), catalog)

        repeat(3) {
            root.addView(panel)
            root.removeView(panel)
        }
        root.addView(panel)
        panel.findViewById<RecyclerView>(R.id.expression_asset_list).performLongClick()

        assertEquals(1, expansions)
    }

    private fun swipe(view: View, fromX: Float, fromY: Float, toX: Float, toY: Float) {
        val downTime = 1_000L
        view.dispatchTouchEvent(MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, fromX, fromY, 0))
        view.dispatchTouchEvent(MotionEvent.obtain(downTime, downTime + 180L, MotionEvent.ACTION_UP, toX, toY, 0))
    }

    private fun visibleState() = ExpressionPanelState().apply {
        beginQuery("你好", requestId = 1)
        applyResults(1, listOf(asset()))
    }

    private fun asset() = ExpressionAsset(
        id = "hello",
        type = "prebuilt",
        format = "webp",
        version = "v1",
        fileName = "templates/hello.webp",
        sha256 = "a".repeat(64),
        width = 512,
        height = 512,
    )
}
