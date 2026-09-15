package com.yuyan.imemodule.view

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.preference.PreferenceManager
import androidx.emoji2.text.EmojiCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.R
import com.yuyan.imemodule.adapter.CandidatesMenuAdapter
import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.callback.CandidateViewListener
import com.yuyan.imemodule.candidate.FloatCandidateBar
import com.yuyan.imemodule.data.emojicon.YuyanEmojiCompat
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.data.theme.Theme
import com.yuyan.imemodule.data.theme.ThemePreset
import com.yuyan.imemodule.database.DataBaseKT
import com.yuyan.imemodule.database.entry.SkbFun
import com.yuyan.imemodule.keyboard.InputView
import com.yuyan.imemodule.keyboard.KeyboardPreviewView
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.prefs.behavior.SkbMenuMode
import com.yuyan.imemodule.service.DecodingInfo
import com.yuyan.imemodule.service.ImeService
import com.yuyan.imemodule.singleton.EnvironmentSingleton
import com.yuyan.inputmethod.core.CandidateListItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class CandidatesBarTest {
    private lateinit var context: Context
    private lateinit var service: ImeService
    private var databaseSnapshot: List<SkbFun> = emptyList()
    private lateinit var originalTheme: Theme
    private var originalCandidateHeight = 0
    private var originalComposingHeight = 0
    private var originalCandidateRowHeight = 0
    private var originalInputAreaHeight = 0

    @Test
    fun `未选前缀时真实拼音行已绑定整段输入显示`() {
        val engine = com.yuyan.inputmethod.RimeEngine
        val stack = engine::class.java.getDeclaredField("keyRecordStack").run {
            isAccessible = true; get(engine) as com.yuyan.inputmethod.data.KeyRecordStack
        }
        @Suppress("UNCHECKED_CAST")
        val records = stack::class.java.getDeclaredField("keyRecords").run {
            isAccessible = true; get(stack) as MutableList<com.yuyan.inputmethod.data.InputKey>
        }
        val mode = com.yuyan.imemodule.manager.InputModeSwitcher
        val modeField = mode::class.java.getDeclaredField("mInputMode").apply { isAccessible = true }
        val previousMode = modeField.getInt(mode)
        try {
            modeField.setInt(mode, com.yuyan.imemodule.manager.InputModeSwitcher.MODE_T9_CHINESE)
            stack.clear(); engine.clearCachedCompositionForSchemaSwitch()
            "9628924726448264".forEach { records.add(com.yuyan.inputmethod.data.InputKey.T9Key("ADGJMPTW"[it - '2'])) }
            engine::class.java.getDeclaredField("nativeCandidateMetadata").apply {
                isAccessible = true
                set(engine, com.yuyan.imemodule.data.completion.CandidateSelection(listOf(
                    com.yuyan.imemodule.data.completion.RankedCandidate("我不再彷徨", "wo bu zai pang huang", 0),
                ), 1))
            }
            engine.showComposition = "wo'bu'zai'726448264"
            DecodingInfo.cacheCandidates(arrayOf(CandidateListItem("wo bu zai", "我不在")))
            val bar = CandidatesBar(context, null).apply { initialize(RecordingCandidateListener()); showCandidates() }
            assertEquals("wo'bu'zai'pang'huang", bar.privateField<TextView>("mComposingView").text.toString())
            assertEquals("wo'bu'zai'726448264", engine.showComposition)
            assertEquals("我不在", DecodingInfo.candidates.single().text)
        } finally {
            stack.clear(); engine.clearCachedCompositionForSchemaSwitch()
            modeField.setInt(mode, previousMode)
        }
    }

    @Test
    @Config(qualifiers = "w369dp-h820dp-520dpi")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun `长拼音可以横向查看全文而不是尾部省略`() {
        val bar = CandidatesBar(context, null).apply { initialize(RecordingCandidateListener()) }
        val composing = bar.privateField<TextView>("mComposingView")
        composing.text = "wo'bu'zai'pang'huang'".repeat(6)
        bar.refreshComposingRow()
        val width = dp(250)
        bar.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp(300), View.MeasureSpec.AT_MOST))
        bar.layout(0, 0, bar.measuredWidth, bar.measuredHeight)
        assertEquals(null, composing.ellipsize)
        assertTrue("全文仍保留在可滚动容器内", composing.parent is android.widget.HorizontalScrollView)
        val scroll = composing.parent as android.widget.HorizontalScrollView
        scroll.isSmoothScrollingEnabled = false
        assertTrue(composing.measuredWidth > scroll.measuredWidth)
        scroll.fullScroll(View.FOCUS_RIGHT)
        assertTrue("能滚到末尾", scroll.scrollX > 0)
        assertEquals(composing.text.length, composing.layout.getLineEnd(0))
    }

    @Test
    @Config(qualifiers = "w369dp-h820dp-520dpi")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun `候选汉字和拼音局部放大不修改全局字号`() {
        val env = EnvironmentSingleton.instance
        val baselineCandidate = env.candidateTextSize
        val baselineComposing = env.composingTextSize
        // 使用生产候选缓存入口
        DecodingInfo.cacheCandidates(arrayOf(CandidateListItem("", "你")))
        val adapter = com.yuyan.imemodule.adapter.CandidatesBarAdapter(context)
        val holder = adapter.onCreateViewHolder(FrameLayout(context), 0)
        adapter.onBindViewHolder(holder, 0)
        assertEquals(baselineCandidate * com.yuyan.imemodule.adapter.CandidatesBarAdapter.CANDIDATE_TEXT_SCALE * context.resources.displayMetrics.density, holder.textView.textSize, 0.01f)
        val bar = CandidatesBar(context, null).apply { initialize(RecordingCandidateListener()); showCandidates() }
        val composing = bar.privateField<TextView>("mComposingView")
        assertEquals(baselineComposing * CandidatesBar.COMPOSING_TEXT_SCALE * context.resources.displayMetrics.density, composing.textSize, 0.01f)
        val composingMetrics = composing.paint.fontMetrics
        assertTrue(
            "拼音fontHeight=" + (composingMetrics.descent - composingMetrics.ascent) +
                " row=" + composing.layoutParams.height,
            composingMetrics.descent - composingMetrics.ascent <= composing.layoutParams.height,
        )
        val candidateMetrics = holder.textView.paint.fontMetrics
        assertTrue("汉字字体高度不能超过行高", candidateMetrics.descent - candidateMetrics.ascent <= env.effectiveCandidateRowHeight(context.resources.displayMetrics.density))
        bar.measure(View.MeasureSpec.makeMeasureSpec(env.skbWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp(300), View.MeasureSpec.AT_MOST))
        bar.layout(0, 0, bar.measuredWidth, bar.measuredHeight)
        assertEquals(env.effectiveCandidateRowHeight(context.resources.displayMetrics.density), bar.measuredHeight)
        assertEquals(baselineCandidate, env.candidateTextSize, 0f)
        assertEquals(baselineComposing, env.composingTextSize, 0f)
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun `拼音和斗图共行四种状态无重叠无幽灵高度`() {
        val bar = CandidatesBar(context, null).apply { initialize(RecordingCandidateListener()) }
        val action = TextView(context).apply { text = "AI斗图"; visibility = View.GONE }
        bar.setExpressionAction(action)
        val composing = bar.privateField<TextView>("mComposingView")
        val row = bar.privateField<View>("composingRow")
        val viewport = bar.privateField<android.widget.HorizontalScrollView>("mComposingScroll")
        for (hasText in listOf(false, true)) for (hasResults in listOf(false, true)) {
            composing.text = if (hasText) "ni hao shi jie ".repeat(30) else ""
            action.visibility = if (hasResults) View.VISIBLE else View.GONE
            bar.refreshComposingRow()
            bar.measure(View.MeasureSpec.makeMeasureSpec(EnvironmentSingleton.instance.skbWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dp(300), View.MeasureSpec.AT_MOST))
            bar.layout(0, 0, bar.measuredWidth, bar.measuredHeight)
            assertEquals(if (hasText || hasResults) View.VISIBLE else View.GONE, row.visibility)
            assertEquals(EnvironmentSingleton.instance.effectiveCandidateRowHeight(context.resources.displayMetrics.density) +
                if (hasText || hasResults) row.height else 0, bar.height)
            if (hasResults) {
                assertTrue("拼音可视区域不能覆盖斗图按钮", viewport.right <= action.left)
                assertTrue(viewport.clipChildren && viewport.clipToPadding)
                assertTrue(kotlin.math.abs(viewport.top + viewport.height / 2 - action.top - action.height / 2) <= 1)
            }
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Launcher::class.java.getDeclaredField("context").apply {
            isAccessible = true
            set(Launcher.instance, context)
        }
        AppPrefs.init(PreferenceManager.getDefaultSharedPreferences(context))
        ThemeManager.init(context.resources.configuration)
        originalTheme = ThemeManager.prefs.normalModeTheme.getValue()
        ThemeManager.setNormalModeTheme(ThemePreset.MaterialLight)
        EmojiCompat.init(object : EmojiCompat.Config(EmojiCompat.MetadataRepoLoader { }) {})
        YuyanEmojiCompat.init(context)
        EnvironmentSingleton.instance.initData(context)
        originalCandidateHeight = EnvironmentSingleton.instance.heightForCandidatesArea
        originalComposingHeight = EnvironmentSingleton.instance.heightForcomposing
        originalCandidateRowHeight = EnvironmentSingleton.instance.heightForCandidates
        originalInputAreaHeight = EnvironmentSingleton.instance.inputAreaHeight
        val dao = DataBaseKT.instance.skbFunDao()
        databaseSnapshot = dao.getAllMenu() + dao.getALlBarMenu()
        dao.deleteAll()
        dao.insertAll(
            listOf(
                SkbFun(SkbMenuMode.ClipBoard.name, isKeep = 1, position = 0),
            ),
        )
        DecodingInfo.candidatesLiveData.value = emptyList()
        service = Robolectric.buildService(ImeService::class.java).create().get()
    }

    @After
    fun tearDown() {
        service.onDestroy()
        DataBaseKT.instance.skbFunDao().run {
            deleteAll()
            insertAll(databaseSnapshot)
        }
        ThemeManager.setNormalModeTheme(originalTheme)
        EnvironmentSingleton.instance.heightForCandidatesArea = originalCandidateHeight
        EnvironmentSingleton.instance.heightForcomposing = originalComposingHeight
        EnvironmentSingleton.instance.heightForCandidates = originalCandidateRowHeight
        EnvironmentSingleton.instance.inputAreaHeight = originalInputAreaHeight
        DecodingInfo.candidatesLiveData.value = emptyList()
    }


    @Test
    fun `数据库工具栏按用户position并以主键稳定决胜`() {
        val dao = DataBaseKT.instance.skbFunDao()
        dao.deleteAll()
        dao.insertAll(
            listOf(
                SkbFun(SkbMenuMode.Phrases.name, isKeep = 1, position = 30),
                SkbFun(SkbMenuMode.Voice.name, isKeep = 1, position = 5),
                SkbFun(SkbMenuMode.TextEdit.name, isKeep = 1, position = 10),
                SkbFun(SkbMenuMode.ClipBoard.name, isKeep = 1, position = 10),
            ),
        )

        assertEquals(
            listOf(
                SkbMenuMode.Voice.name,
                SkbMenuMode.ClipBoard.name,
                SkbMenuMode.TextEdit.name,
                SkbMenuMode.Phrases.name,
            ),
            dao.getALlBarMenu().map(SkbFun::name),
        )
    }


    @Test
    fun `真实InputView浅深主题切换同步固定按钮工具项和AI面板`() {
        val inputView = service.onCreateInputView() as InputView
        val bar = inputView.mSkbCandidatesBarView
        bar.showCandidates()
        val adapter = bar.privateField<CandidatesMenuAdapter>("mCandidatesMenuAdapter")
        val aiPosition = adapter.items.indexOfFirst { it.item?.skbMenuMode == SkbMenuMode.AiDoutu }
        val holder = adapter.onCreateViewHolder(FrameLayout(context), adapter.getItemViewType(aiPosition))
        adapter.onBindViewHolder(holder, aiPosition)
        val left = bar.privateField<View>("mIvMenuSetting")
        val right = bar.privateField<View>("mMenuRightArrowBtn")
        val panelTool = inputView.findViewById<View>(R.id.expression_tool_row)
        val panelEnable = inputView.findViewById<TextView>(R.id.expression_enable)
        val lightLeftPress = pressedColor(left)
        val lightRightPress = pressedColor(right)
        val lightItemPress = pressedColor(holder.itemView)
        val lightPanel = (panelTool.background as ColorDrawable).color
        val lightPanelText = panelEnable.currentTextColor

        ThemeManager.setNormalModeTheme(ThemePreset.MaterialDark)
        inputView.updateTheme()
        adapter.onBindViewHolder(holder, aiPosition)

        assertTrue(lightLeftPress != pressedColor(left))
        assertTrue(lightRightPress != pressedColor(right))
        assertTrue(lightItemPress != pressedColor(holder.itemView))
        assertTrue(lightPanel != (panelTool.background as ColorDrawable).color)
        assertEquals(lightPanelText, panelEnable.currentTextColor)
        assertEquals(ThemeManager.activeTheme.keyTextColor, holder.entranceIconImageView.imageTintList?.defaultColor)
    }

    @Test
    fun `空候选生产栏固定左右并完整消费五个参考按钮`() {
        val inputView = service.onCreateInputView() as InputView
        val bar = inputView.mSkbCandidatesBarView

        bar.showCandidates()

        val adapter = bar.privateField<CandidatesMenuAdapter>("mCandidatesMenuAdapter")
        assertEquals(
            listOf(
                SkbMenuMode.Emojicon,
                SkbMenuMode.QuickKeyboard,
                SkbMenuMode.ClipBoard,
                SkbMenuMode.TextEdit,
                SkbMenuMode.AiDoutu,
            ),
            adapter.items.map { it.item?.skbMenuMode },
        )
        val left = bar.privateField<View>("mIvMenuSetting")
        val right = bar.privateField<View>("mMenuRightArrowBtn")
        assertTrue(left.isClickable)
        assertTrue(right.isClickable)
        assertTrue(left.minimumWidth >= dp(44))
        assertTrue(right.minimumWidth >= dp(44))
        assertFalse(left.contentDescription.isNullOrBlank())
        assertFalse(right.contentDescription.isNullOrBlank())
        assertNotNull(left.background)
        assertNotNull(right.background)
    }

    @Test
    @Config(qualifiers = "w800dp-h800dp-xxhdpi")
    fun `三倍密度候选高度由展示行加四十四dp点击行组成且空工具栏不留展示行空白`() {
        EnvironmentSingleton.instance.heightForcomposing = dp(12)
        EnvironmentSingleton.instance.heightForCandidates = dp(24)
        EnvironmentSingleton.instance.heightForCandidatesArea = dp(36)
        EnvironmentSingleton.instance.inputAreaHeight =
            EnvironmentSingleton.instance.skbHeight + EnvironmentSingleton.instance.heightForCandidatesArea
        val inputView = service.onCreateInputView() as InputView
        val host = FrameLayout(context).apply { addView(inputView) }
        host.measure(
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.AT_MOST),
        )
        host.layout(0, 0, host.measuredWidth, host.measuredHeight)
        inputView.refreshExpressionLayoutBudget()
        val bar = inputView.mSkbCandidatesBarView
        val minimum = dp(44)
        assertEquals(dp(12) + minimum, EnvironmentSingleton.instance.effectiveCandidatesAreaHeight(context.resources.displayMetrics.density))
        assertEquals(minimum, EnvironmentSingleton.instance.effectiveCandidateRowHeight(context.resources.displayMetrics.density))
        val left = bar.privateField<View>("mIvMenuSetting")
        val right = bar.privateField<View>("mMenuRightArrowBtn")
        val keyboard = inputView.findViewById<View>(R.id.skb_input_keyboard_view)

        assertEquals(minimum, bar.measuredHeight)
        assertTrue(left.measuredWidth >= minimum && left.measuredHeight >= minimum)
        assertTrue(right.measuredWidth >= minimum && right.measuredHeight >= minimum)
        assertTrue(
            inputView.expressionLayoutBudget.reservedNonPanelHeightPx >=
                bar.measuredHeight + keyboard.measuredHeight,
        )

        val flower = bar.privateField<View>("mFlowerType")
        assertTrue(flower.minimumWidth >= minimum && flower.minimumHeight >= minimum)

        val preview = KeyboardPreviewView(context).apply {
            layoutParams = FrameLayout.LayoutParams(1, 1)
            setTheme(ThemeManager.activeTheme)
        }
        assertEquals(EnvironmentSingleton.instance.skbHeight + minimum, preview.layoutParams.height)
        preview.layoutParams.height = 1
        preview.setTheme(ThemeManager.activeTheme, ColorDrawable(android.graphics.Color.TRANSPARENT))
        assertEquals(EnvironmentSingleton.instance.skbHeight + minimum, preview.layoutParams.height)
    }

    @Test
    @Config(qualifiers = "w800dp-h800dp-xxhdpi")
    fun `真实键盘预览两种主题入口均紧接四十四dp工具栏且底部无空白`() {
        configureShortCandidateRows()
        val plain = KeyboardPreviewView(context).apply {
            layoutParams = FrameLayout.LayoutParams(1, 1)
            setTheme(ThemeManager.activeTheme)
        }
        val customBackground = KeyboardPreviewView(context).apply {
            layoutParams = FrameLayout.LayoutParams(1, 1)
            setTheme(ThemeManager.activeTheme, ColorDrawable(android.graphics.Color.TRANSPARENT))
        }

        listOf(plain, customBackground).forEach(::assertPreviewHierarchyHasNoBlank)
    }

    @Test
    @Config(qualifiers = "w800dp-h800dp-xxhdpi")
    fun `真实普通候选层级展示行与点击行不互相裁剪且点击命中监听`() {
        configureShortCandidateRows()
        DecodingInfo.cacheCandidates(arrayOf(CandidateListItem("", "短")))
        val clicks = RecordingCandidateListener()
        val bar = CandidatesBar(context, null).apply { initialize(clicks); showCandidates() }
        measureInHost(bar, 2400, dp(56))
        val composing = bar.privateField<View>("mComposingView")
        val row = bar.privateField<View>("candidatesData")
        val recycler = bar.privateField<RecyclerView>("mRVCandidates")
        val right = bar.privateField<View>("mRightArrowBtn")
        val item = requireNotNull(recycler.getChildAt(0))

        assertEquals(dp(44), bar.height)
        assertEquals(View.GONE, bar.privateField<View>("composingRow").visibility)
        assertFalse(composing.isClickable)
        assertFalse(composing.hasOnClickListeners())
        listOf(row, recycler, right, item).forEach { target ->
            assertTrue("${target.javaClass.simpleName}=${target.width}x${target.height}", target.width >= dp(44) && target.height >= dp(44))
            assertTrue("${target.javaClass.simpleName} top=${target.top} bottom=${target.bottom} bar=${bar.height}", target.bottom <= bar.height)
        }
        item.performClick()
        right.performClick()
        assertEquals(listOf(0), clicks.choices)
        assertEquals(listOf(0), clicks.moreLevels)
    }

    @Test
    @Config(qualifiers = "w800dp-h800dp-xxhdpi")
    fun `真实悬浮候选层级同样使用展示行加四十四dp点击行`() {
        configureShortCandidateRows()
        DecodingInfo.cacheCandidates(arrayOf(CandidateListItem("", "短")))
        val clicks = RecordingCandidateListener()
        val bar = FloatCandidateBar(context, null).apply { initialize(clicks); showCandidates() }
        measureInHost(bar, 2400, dp(56))
        val composing = bar.privateField<View>("mComposingView")
        val row = bar.privateField<View>("candidatesData")
        val recycler = bar.privateField<RecyclerView>("mRVCandidates")
        val item = requireNotNull(recycler.getChildAt(0))

        assertEquals(dp(56), bar.height)
        assertEquals(dp(12), composing.height)
        assertFalse(composing.isClickable)
        listOf(row, recycler, item).forEach { target ->
            assertTrue("${target.javaClass.simpleName}=${target.width}x${target.height}", target.width >= dp(44) && target.height >= dp(44))
            assertTrue("${target.javaClass.simpleName} top=${target.top} bottom=${target.bottom} bar=${bar.height}", target.bottom <= bar.height)
        }
        item.performClick()
        assertEquals(listOf(0), clicks.choices)
    }

    private fun pressedColor(view: View): Int {
        val background = view.background as StateListDrawable
        background.state = intArrayOf(android.R.attr.state_pressed)
        return (background.current as GradientDrawable).color?.defaultColor
            ?: error("pressed color missing")
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> CandidatesBar.privateField(name: String): T =
        CandidatesBar::class.java.getDeclaredField(name).let {
            it.isAccessible = true
            it.get(this) as T
        }

    @Suppress("UNCHECKED_CAST")
    private fun <T> FloatCandidateBar.privateField(name: String): T =
        FloatCandidateBar::class.java.getDeclaredField(name).let {
            it.isAccessible = true
            it.get(this) as T
        }

    private fun configureShortCandidateRows() {
        EnvironmentSingleton.instance.heightForcomposing = dp(12)
        EnvironmentSingleton.instance.heightForCandidates = dp(24)
        EnvironmentSingleton.instance.heightForCandidatesArea = dp(36)
        EnvironmentSingleton.instance.inputAreaHeight = EnvironmentSingleton.instance.skbHeight + dp(36)
    }

    private fun measureInHost(view: View, width: Int, height: Int) {
        val host = FrameLayout(context).apply { addView(view) }
        host.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        host.layout(0, 0, host.measuredWidth, host.measuredHeight)
    }

    private fun assertPreviewHierarchyHasNoBlank(preview: KeyboardPreviewView) {
        val host = FrameLayout(context).apply { addView(preview) }
        host.measure(
            View.MeasureSpec.makeMeasureSpec(EnvironmentSingleton.instance.skbWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp(800), View.MeasureSpec.AT_MOST),
        )
        host.layout(0, 0, host.measuredWidth, host.measuredHeight)
        val sdkPreviewRoot = preview.getChildAt(0) as ViewGroup
        val candidates = sdkPreviewRoot.findViewById<View>(R.id.candidates_bar)
        val keyboard = sdkPreviewRoot.findViewById<View>(R.id.skb_input_keyboard_view)

        assertEquals(dp(44), candidates.height)
        assertEquals(candidates.bottom, keyboard.top)
        assertEquals(candidates.bottom + keyboard.height, sdkPreviewRoot.height)
        assertEquals(sdkPreviewRoot.height, preview.height)
        assertEquals(EnvironmentSingleton.instance.skbHeight + dp(44), preview.height)
    }

    private class RecordingCandidateListener : CandidateViewListener {
        val choices = mutableListOf<Int>()
        val moreLevels = mutableListOf<Int>()
        override fun onClickChoice(choiceId: Int) { choices += choiceId }
        override fun onClickMore(level: Int) { moreLevels += level }
        override fun onClickMenu(skbMenuMode: SkbMenuMode) = Unit
        override fun onClickClearCandidate() = Unit
        override fun onClickClearClipBoard() = Unit
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
