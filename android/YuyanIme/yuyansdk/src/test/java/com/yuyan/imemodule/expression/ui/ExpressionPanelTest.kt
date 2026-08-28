package com.yuyan.imemodule.expression.ui

import android.app.Activity
import android.content.Context
import android.view.InputDevice
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
import com.yuyan.imemodule.expression.model.EmojiBase
import com.yuyan.imemodule.expression.model.ExpressionAsset
import com.yuyan.imemodule.expression.model.ExpressionCatalogDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
    fun `开启时显示三标签和操作入口`() {
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
    @Config(qualifiers = "w360dp-h640dp-xxhdpi")
    fun `小屏生产面板所有可见操作实际边界至少四十四dp`() {
        val panel = ExpressionPanel(context)
        panel.render(visibleState(), catalog)
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.AT_MOST),
        )
        panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
        val minimum = (44f * context.resources.displayMetrics.density).toInt()

        listOf(
            R.id.expression_tab_recommended,
            R.id.expression_tab_templates,
            R.id.expression_tab_emoji,
            R.id.expression_more,
            R.id.expression_close,
            R.id.expression_enable,
        ).forEach { id ->
            val target = panel.findViewById<View>(id)
            assertTrue("id=$id width=${target.width}", target.width >= minimum)
            assertTrue("id=$id height=${target.height}", target.height >= minimum)
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-xxhdpi")
    fun `小屏Emoji选择页返回与表情项实际边界至少四十四dp且内容不裁切`() {
        val emojiCatalog = emojiCatalog()
        val state = visibleState().apply { selectTab(ExpressionPanelTab.EMOJI_SYNTHESIS) }
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        activity.setContentView(root)
        val panel = ExpressionPanel(activity)
        root.addView(panel)
        panel.render(state, emojiCatalog)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.AT_MOST),
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        val picker = panel.findViewById<EmojiCombinationPicker>(R.id.expression_emoji_picker)
        val list = picker.findViewById<RecyclerView>(R.id.expression_emoji_list)
        val item = requireNotNull(list.getChildAt(0))
        item.performClick()
        val back = picker.findViewById<View>(R.id.expression_emoji_back)
        val minimum = (44f * context.resources.displayMetrics.density).toInt()

        assertEquals(View.VISIBLE, back.visibility)
        assertTrue("emoji back ${back.width}x${back.height}", back.width >= minimum && back.height >= minimum)
        assertTrue(
            "emoji item ${item.width}x${item.height}",
            item.width >= minimum && item.height >= minimum,
        )
        assertTrue(picker.height <= panel.findViewById<View>(R.id.expression_content).height)
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land-xxhdpi")
    fun `横屏Emoji选择页为标题返回与表情项保留两个四十四dp层`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        activity.setContentView(root)
        val panel = ExpressionPanel(activity)
        root.addView(panel)
        panel.setAvailableLayoutHeight(availableHeightPx = 1080, reservedKeyboardHeightPx = 480)
        panel.render(
            visibleState().apply { selectTab(ExpressionPanelTab.EMOJI_SYNTHESIS) },
            emojiCatalog(),
        )
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.AT_MOST),
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)

        assertTrue(panel.findViewById<View>(R.id.expression_content).height >= dp(88))
        assertEmojiTargetsAccessible(panel, dp(44))
    }

    @Test
    @Config(qualifiers = "w360dp-h240dp-xxhdpi")
    fun `极端Emoji预算改为四十四dp单层横滑且工具入口仍可访问`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        activity.setContentView(root)
        val panel = ExpressionPanel(activity)
        root.addView(panel)
        panel.setAvailableLayoutHeight(availableHeightPx = 720, reservedKeyboardHeightPx = 456)
        panel.render(
            visibleState().apply { selectTab(ExpressionPanelTab.EMOJI_SYNTHESIS) },
            emojiCatalog(),
        )
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.AT_MOST),
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        val picker = panel.findViewById<EmojiCombinationPicker>(R.id.expression_emoji_picker)

        assertEquals(dp(88), panel.measuredHeight)
        assertEquals(dp(44), panel.findViewById<View>(R.id.expression_content).height)
        assertEquals(LinearLayout.HORIZONTAL, picker.orientation)
        assertEmojiTargetsAccessible(panel, dp(44))
        assertTrue(panel.measuredHeight + 456 <= 720)
    }

    @Test
    @Config(qualifiers = "w360dp-h240dp-xxhdpi")
    fun `单层Emoji初始态可从真实返回入口退出到推荐且保留查询结果`() {
        val state = visibleState().apply { selectTab(ExpressionPanelTab.EMOJI_SYNTHESIS) }
        val panel = createExtremeEmojiPanel(state)
        val back = panel.findViewById<View>(R.id.expression_emoji_back)

        assertEquals(View.GONE, panel.findViewById<View>(R.id.expression_tab_bar).visibility)
        assertEquals(View.VISIBLE, back.visibility)
        assertEquals("退出 Emoji 合成，返回推荐", back.contentDescription)
        assertTrue(back.width >= dp(44) && back.height >= dp(44))

        back.performClick()

        assertEquals(ExpressionPanelTab.RECOMMENDED, state.selectedTab)
        assertEquals("你好", state.query)
        assertEquals(1, state.results.size)
        assertEquals(View.VISIBLE, panel.findViewById<View>(R.id.expression_asset_list).visibility)
    }

    @Test
    @Config(qualifiers = "w360dp-h240dp-xxhdpi")
    fun `单层Emoji选择后返回第一步再返回推荐且隐藏恢复不丢状态`() {
        val state = visibleState().apply {
            hideRecommendations()
            restoreRecommendations()
            selectTab(ExpressionPanelTab.EMOJI_SYNTHESIS)
        }
        val panel = createExtremeEmojiPanel(state)
        val picker = panel.findViewById<EmojiCombinationPicker>(R.id.expression_emoji_picker)
        val item = requireNotNull(picker.findViewById<RecyclerView>(R.id.expression_emoji_list).getChildAt(0))
        val back = picker.findViewById<View>(R.id.expression_emoji_back)

        panel.findViewById<View>(R.id.expression_close).performClick()
        assertEquals(View.GONE, panel.findViewById<View>(R.id.expression_recommendation_section).visibility)
        panel.findViewById<View>(R.id.expression_enable).performClick()
        assertEquals(View.VISIBLE, panel.findViewById<View>(R.id.expression_recommendation_section).visibility)

        item.performClick()
        assertEquals("返回选择第一个表情", back.contentDescription)
        back.performClick()
        assertEquals("退出 Emoji 合成，返回推荐", back.contentDescription)
        assertEquals(ExpressionPanelTab.EMOJI_SYNTHESIS, state.selectedTab)

        back.performClick()

        assertEquals(ExpressionPanelTab.RECOMMENDED, state.selectedTab)
        assertEquals("你好", state.query)
        assertEquals(1, state.results.size)
    }

    @Test
    fun `相同展示模式重复render保留LayoutManager与滚动上下文`() {
        val panel = ExpressionPanel(context)
        val state = visibleState()
        val list = panel.findViewById<RecyclerView>(R.id.expression_asset_list)

        panel.render(state, catalog)
        val compactManager = list.layoutManager
        panel.render(state, catalog)
        assertSame(compactManager, list.layoutManager)

        state.expand()
        panel.render(state, catalog)
        val expandedManager = list.layoutManager
        panel.render(state, catalog)
        assertSame(expandedManager, list.layoutManager)
    }


    @Test
    @Config(qualifiers = "w640dp-h360dp-land-xxhdpi")
    fun `一九二零乘一零八零三倍密度横屏生产面板不挤出保留键盘高度`() {
        assertEquals(3f, context.resources.displayMetrics.density)
        assertEquals(android.content.res.Configuration.ORIENTATION_LANDSCAPE, context.resources.configuration.orientation)
        val panel = ExpressionPanel(context)
        panel.setAvailableLayoutHeight(availableHeightPx = 1080, reservedKeyboardHeightPx = 720)
        panel.render(visibleState(), catalog)
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.AT_MOST),
        )

        assertTrue(panel.measuredHeight <= 360)
        assertEquals(
            panel.measuredHeight,
            panel.findViewById<View>(R.id.expression_tab_bar).layoutParams.height +
                panel.findViewById<View>(R.id.expression_content).layoutParams.height +
                panel.findViewById<View>(R.id.expression_tool_row).layoutParams.height,
        )
    }

    @Test
    @Config(qualifiers = "w360dp-h240dp-xxhdpi")
    fun `矮屏生产面板继续压缩且不遮键盘`() {
        assertEquals(3f, context.resources.displayMetrics.density)
        val panel = ExpressionPanel(context)
        panel.setAvailableLayoutHeight(availableHeightPx = 720, reservedKeyboardHeightPx = 600)
        panel.render(visibleState(), catalog)
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.AT_MOST),
        )
        assertEquals(132, panel.measuredHeight)
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun `竖屏生产面板受设计最大高度限制`() {
        assertEquals(3f, context.resources.displayMetrics.density)
        assertEquals(android.content.res.Configuration.ORIENTATION_PORTRAIT, context.resources.configuration.orientation)
        val panel = ExpressionPanel(context)
        panel.setAvailableLayoutHeight(availableHeightPx = 2400, reservedKeyboardHeightPx = 900)
        panel.render(visibleState(), catalog)
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.AT_MOST),
        )

        assertEquals(552, panel.measuredHeight)
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
    fun `多指加入退出后即使主指上滑也不会展开`() {
        val panel = ExpressionPanel(context)
        var expansions = 0
        panel.onExpandRequested = { expansions += 1 }
        panel.render(visibleState(), catalog)
        val list = panel.findViewById<RecyclerView>(R.id.expression_asset_list)
        val downTime = 1L

        list.dispatchTouchEvent(motion(downTime, 1L, MotionEvent.ACTION_DOWN, listOf(pointer(7, 120f, 120f))))
        list.dispatchTouchEvent(
            motion(
                downTime,
                40L,
                MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                listOf(pointer(7, 120f, 110f), pointer(9, 180f, 100f)),
            ),
        )
        list.dispatchTouchEvent(
            motion(
                downTime,
                80L,
                MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                listOf(pointer(7, 120f, 80f), pointer(9, 180f, 100f)),
            ),
        )
        list.dispatchTouchEvent(motion(downTime, 160L, MotionEvent.ACTION_UP, listOf(pointer(7, 120f, 20f))))

        assertEquals(0, expansions)
    }

    @Test
    fun `取消手势后的迟到抬起不会展开`() {
        val panel = ExpressionPanel(context)
        var expansions = 0
        panel.onExpandRequested = { expansions += 1 }
        panel.render(visibleState(), catalog)
        val list = panel.findViewById<RecyclerView>(R.id.expression_asset_list)

        list.dispatchTouchEvent(motion(1L, 1L, MotionEvent.ACTION_DOWN, listOf(pointer(3, 120f, 120f))))
        list.dispatchTouchEvent(motion(1L, 80L, MotionEvent.ACTION_CANCEL, listOf(pointer(3, 120f, 70f))))
        list.dispatchTouchEvent(motion(1L, 160L, MotionEvent.ACTION_UP, listOf(pointer(3, 120f, 20f))))

        assertEquals(0, expansions)
    }

    @Test
    fun `非零主指ID的单指上滑仍可展开`() {
        val panel = ExpressionPanel(context)
        var expansions = 0
        panel.onExpandRequested = { expansions += 1 }
        panel.render(visibleState(), catalog)
        val list = panel.findViewById<RecyclerView>(R.id.expression_asset_list)

        list.dispatchTouchEvent(motion(1L, 1L, MotionEvent.ACTION_DOWN, listOf(pointer(5, 120f, 120f))))
        list.dispatchTouchEvent(motion(1L, 160L, MotionEvent.ACTION_UP, listOf(pointer(5, 120f, 20f))))

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
    fun `Emoji合成标题返回图标和背景随浅深主题即时刷新`() {
        val state = visibleState().apply { selectTab(ExpressionPanelTab.EMOJI_SYNTHESIS) }
        val panel = ExpressionPanel(context)
        ThemeManager.setNormalModeTheme(ThemePreset.MaterialLight)
        panel.render(state, emojiCatalog())
        val picker = panel.findViewById<EmojiCombinationPicker>(R.id.expression_emoji_picker)
        val title = picker.findViewById<TextView>(R.id.expression_emoji_title)
        val back = picker.findViewById<android.widget.ImageButton>(R.id.expression_emoji_back)
        val light = ThemeManager.activeTheme
        assertEquals(light.keyTextColor, title.currentTextColor)
        assertEquals(light.keyTextColor, back.imageTintList?.defaultColor)
        assertEquals(light.keyboardColor, (picker.background as android.graphics.drawable.ColorDrawable).color)

        ThemeManager.setNormalModeTheme(ThemePreset.MaterialDark)
        panel.updateTheme()
        val dark = ThemeManager.activeTheme

        assertEquals(dark.keyTextColor, title.currentTextColor)
        assertEquals(dark.keyTextColor, back.imageTintList?.defaultColor)
        assertEquals(dark.keyboardColor, (picker.background as android.graphics.drawable.ColorDrawable).color)
        assertNotEquals(light.keyTextColor, dark.keyTextColor)
    }

    @Test
    fun `一倍半和二倍字体单手窄屏三标签完整且操作键不裁切`() {
        listOf(1.5f, 2f).forEach { fontScale ->
            val scaled = context.createConfigurationContext(
                android.content.res.Configuration(context.resources.configuration).apply { this.fontScale = fontScale },
            )
            val panel = ExpressionPanel(scaled)
            panel.render(visibleState(), catalog)
            val width = (288f * scaled.resources.displayMetrics.density).toInt()
            panel.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dp(300), View.MeasureSpec.AT_MOST),
            )
            panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)

            val expected = mapOf(
                R.id.expression_tab_recommended to "推荐",
                R.id.expression_tab_templates to "AI合成",
                R.id.expression_tab_emoji to "Emoji合成",
            )
            expected.forEach { (id, label) ->
                val tab = panel.findViewById<TextView>(id)
                assertEquals(label, tab.text.toString())
                assertTrue("scale=$fontScale tab=$label left=${tab.left} right=${tab.right} width=$width", tab.left >= 0 && tab.right <= width)
                assertTrue(tab.width >= (44f * scaled.resources.displayMetrics.density).toInt())
                assertTrue(tab.isClickable)
            }
            listOf(R.id.expression_more, R.id.expression_close).forEach { id ->
                val action = panel.findViewById<View>(id)
                assertTrue(action.left >= 0 && action.right <= width)
                assertTrue(action.width >= (44f * scaled.resources.displayMetrics.density).toInt())
                assertTrue(action.isClickable)
            }
        }
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

    private data class Pointer(val id: Int, val x: Float, val y: Float)

    private fun pointer(id: Int, x: Float, y: Float) = Pointer(id, x, y)

    private fun motion(
        downTime: Long,
        eventTime: Long,
        action: Int,
        pointers: List<Pointer>,
    ): MotionEvent {
        val properties = pointers.map { pointer ->
            MotionEvent.PointerProperties().apply {
                id = pointer.id
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }.toTypedArray()
        val coordinates = pointers.map { pointer ->
            MotionEvent.PointerCoords().apply {
                x = pointer.x
                y = pointer.y
                pressure = 1f
                size = 1f
            }
        }.toTypedArray()
        return MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            pointers.size,
            properties,
            coordinates,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0,
        )
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

    private fun emojiCatalog() = ExpressionCatalog(
        ExpressionCatalogDocument(
            version = "v1",
            templates = emptyList(),
            emojiBases = listOf(
                EmojiBase(
                    id = "smile",
                    name = "微笑",
                    fileName = "missing.webp",
                    sha256 = "a".repeat(64),
                    version = "v1",
                    width = 128,
                    height = 128,
                    sortOrder = 0,
                ),
            ),
            emojiCombinations = emptyList(),
        ),
    )

    private fun assertEmojiTargetsAccessible(panel: ExpressionPanel, minimum: Int) {
        val picker = panel.findViewById<EmojiCombinationPicker>(R.id.expression_emoji_picker)
        val list = picker.findViewById<RecyclerView>(R.id.expression_emoji_list)
        val item = requireNotNull(list.getChildAt(0))
        item.performClick()
        val back = picker.findViewById<View>(R.id.expression_emoji_back)
        assertEquals(View.VISIBLE, back.visibility)
        assertTrue("emoji back ${back.width}x${back.height}", back.width >= minimum && back.height >= minimum)
        assertTrue("emoji item ${item.width}x${item.height}", item.width >= minimum && item.height >= minimum)
    }

    private fun createExtremeEmojiPanel(state: ExpressionPanelState): ExpressionPanel {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        activity.setContentView(root)
        val panel = ExpressionPanel(activity)
        root.addView(panel)
        panel.onTabSelected = { tab ->
            state.selectTab(tab)
            panel.render(state, emojiCatalog())
        }
        panel.onRecommendationVisibilityChange = { visible ->
            if (visible) state.restoreRecommendations() else state.hideRecommendations()
            panel.render(state, emojiCatalog())
        }
        panel.setAvailableLayoutHeight(availableHeightPx = 720, reservedKeyboardHeightPx = 456)
        panel.render(state, emojiCatalog())
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.AT_MOST),
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        return panel
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

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
