package com.yuyan.imemodule.keyboard

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.view.inputmethod.EditorInfo
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.yuyan.imemodule.R
import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.data.emojicon.YuyanEmojiCompat
import com.yuyan.imemodule.data.collect.ServerConfig
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.expression.ExpressionCatalog
import com.yuyan.imemodule.expression.ExpressionPanelPresentation
import com.yuyan.imemodule.expression.ExpressionPanelState
import com.yuyan.imemodule.expression.ExpressionComposingTextSource
import com.yuyan.imemodule.expression.ExpressionCommitKind
import com.yuyan.imemodule.expression.ExpressionPanelTab
import com.yuyan.imemodule.expression.model.EmojiBase
import com.yuyan.imemodule.expression.model.EmojiCombination
import com.yuyan.imemodule.expression.model.ExpressionAsset
import com.yuyan.imemodule.expression.model.ExpressionCatalogDocument
import com.yuyan.imemodule.expression.ui.EmojiCombinationPicker
import com.yuyan.imemodule.expression.ui.ExpressionPanel
import com.yuyan.imemodule.keyboard.container.SymbolContainer
import com.yuyan.imemodule.keyboard.container.SettingsContainer
import com.yuyan.imemodule.entity.keyboard.SoftKey
import com.yuyan.imemodule.manager.InputModeSwitcher
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.prefs.behavior.SkbMenuMode
import com.yuyan.imemodule.prefs.behavior.SymbolMode
import com.yuyan.imemodule.service.DecodingInfo
import com.yuyan.imemodule.service.ImeService
import com.yuyan.imemodule.singleton.EnvironmentSingleton
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowToast
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

@RunWith(RobolectricTestRunner::class)
class ExpressionManualSearchInputViewTest {
    /** 线上查询地址不再受 server_url 偏好覆盖；仅本测试替换 getter，保留真实 HTTP 断言。 */
    @Implements(value = ServerConfig::class, isInAndroidSdk = false)
    class LocalServerConfigShadow {
        @Implementation
        fun getBaseUrl(): String = url

        companion object {
            @JvmField var url = ""
        }
    }

    private lateinit var context: Context
    private val services = mutableListOf<ImeService>()
    private val inputViews = mutableListOf<InputView>()
    private var originalAiStickerEnabled = false
    private var originalFloatMode = false
    private var originalFullDisplay = false
    private var originalBottomPadding = 0
    private var originalFloatBottomPadding = 0
    private var originalKeyboardHeightRatio = 0f
    private lateinit var originalConfiguration: Configuration

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        originalConfiguration = Configuration(context.resources.configuration)
        Launcher::class.java.getDeclaredField("context").apply {
            isAccessible = true
            set(Launcher.instance, context)
        }
        AppPrefs.init(PreferenceManager.getDefaultSharedPreferences(context))
        originalAiStickerEnabled = AppPrefs.getInstance().internal.aiStickerEnabled.getValue()
        val internal = AppPrefs.getInstance().internal
        originalFloatMode = internal.keyboardModeFloat.getValue()
        originalFullDisplay = internal.fullDisplayKeyboardEnable.getValue()
        originalBottomPadding = internal.keyboardBottomPadding.getValue()
        originalFloatBottomPadding = internal.keyboardBottomPaddingFloat.getValue()
        originalKeyboardHeightRatio = internal.keyboardHeightRatio.getValue()
        ThemeManager.init(context.resources.configuration)
        YuyanEmojiCompat.init(context)
        EnvironmentSingleton.instance.initData(context)
        InputModeSwitcher.reset()
        DecodingInfo.candidatesLiveData.value = emptyList()
        DecodingInfo.isAssociate = false
        ShadowToast.reset()
    }

    @After
    fun tearDown() {
        inputViews.forEach(InputView::disposeExpressionResources)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        inputViews.clear()
        services.forEach(ImeService::onDestroy)
        services.clear()
        AppPrefs.getInstance().internal.aiStickerEnabled.setValue(originalAiStickerEnabled)
        val internal = AppPrefs.getInstance().internal
        internal.keyboardModeFloat.setValue(originalFloatMode)
        internal.fullDisplayKeyboardEnable.setValue(originalFullDisplay)
        internal.keyboardBottomPadding.setValue(originalBottomPadding)
        internal.keyboardBottomPaddingFloat.setValue(originalFloatBottomPadding)
        internal.keyboardHeightRatio.setValue(originalKeyboardHeightRatio)
        @Suppress("DEPRECATION")
        context.resources.updateConfiguration(originalConfiguration, context.resources.displayMetrics)
        EnvironmentSingleton.instance.systemNavbarWindowsBottom = 0
        EnvironmentSingleton.instance.initData(context)
        DecodingInfo.candidatesLiveData.value = emptyList()
        DecodingInfo.isAssociate = false
    }

    @Test
    fun `九宫格候选时显示确定清空和上屏联想时恢复换行`() {
        androidx.emoji2.text.EmojiCompat.init(object : androidx.emoji2.text.EmojiCompat.Config(androidx.emoji2.text.EmojiCompat.MetadataRepoLoader { }) {})
        val inputView = realChatInputView()
        attachAndLayout(inputView, 2400)
        KeyboardManager.instance.switchKeyboard(KeyboardManager.KeyboardType.T9)
        InputModeSwitcher.mToggleStates.imeAction = EditorInfo.IME_ACTION_NONE
        val container = KeyboardManager.instance.currentContainer as com.yuyan.imemodule.keyboard.container.InputBaseContainer
        val keyboard = org.robolectric.util.ReflectionHelpers.getField<TextKeyboard>(container, "mMajorView")
        container.updateStates()
        val enter = keyboard.getSoftKeyboard().getKeyByCode(KeyEvent.KEYCODE_ENTER)!!
        assertEquals("换行", enter.keyLabel)
        DecodingInfo.cacheCandidates(arrayOf(com.yuyan.inputmethod.core.CandidateListItem("", "常用词")))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals("确定", enter.keyLabel)
        assertEquals(com.yuyan.imemodule.entity.keyboard.KeyType.Function, enter.keyType)
        DecodingInfo.cacheCandidates(emptyArray())
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals("换行", enter.keyLabel)
        assertEquals(com.yuyan.imemodule.entity.keyboard.KeyType.AccentKey, enter.keyType)
        DecodingInfo.cacheCandidates(arrayOf(com.yuyan.inputmethod.core.CandidateListItem("", "联想")), associate = true)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals("换行", enter.keyLabel)
        InputModeSwitcher.mToggleStates.imeAction = EditorInfo.IME_ACTION_SEARCH
        container.updateStates()
        assertEquals("搜索", enter.keyLabel)
    }

    @Test
    fun `全键盘候选不切到九宫格专属确认状态`() {
        androidx.emoji2.text.EmojiCompat.init(object : androidx.emoji2.text.EmojiCompat.Config(androidx.emoji2.text.EmojiCompat.MetadataRepoLoader { }) {})
        val inputView = realChatInputView()
        attachAndLayout(inputView, 2400)
        KeyboardManager.instance.switchKeyboard(KeyboardManager.KeyboardType.QWERTY)
        InputModeSwitcher.mToggleStates.imeAction = EditorInfo.IME_ACTION_NONE
        val container = KeyboardManager.instance.currentContainer as com.yuyan.imemodule.keyboard.container.InputBaseContainer
        val keyboard = org.robolectric.util.ReflectionHelpers.getField<TextKeyboard>(container, "mMajorView")
        container.updateStates()
        val enter = keyboard.getSoftKeyboard().getKeyByCode(KeyEvent.KEYCODE_ENTER)!!
        val originalLabel = enter.keyLabel
        val originalType = enter.keyType
        DecodingInfo.cacheCandidates(arrayOf(com.yuyan.inputmethod.core.CandidateListItem("", "常用词")))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(EditorInfo.IME_ACTION_NONE, enter.stateId)
        assertEquals(originalLabel, enter.keyLabel)
        assertEquals(originalType, enter.keyType)
    }

    @Test
    fun `生产拼音行旧斗图入口始终隐藏`() {
        val inputView = realChatInputView()
        val panel = inputView.findViewById<ExpressionPanel>(R.id.expression_panel)
        val button = inputView.findViewById<View>(R.id.expression_enable)
        val state = inputView.expressionState()
        state.beginQuery("谢谢", 700)
        state.applyResults(700, listOf(ExpressionAsset(id="shared", type="prebuilt", format="gif", version="v1",
            fileName="shared.gif", sha256="a".repeat(64), width=240, height=240)))
        panel.render(state, ExpressionCatalog.fromAssets(context))
        assertEquals(View.GONE, button.visibility)
        assertEquals(View.GONE, (button.parent as View).visibility)
        assertEquals(View.VISIBLE, panel.visibility)
    }

    @Test
    fun `工具栏手动打开广覆盖无字GIF再点击关闭第三次重开`() {
        val inputView = realChatInputView()
        inputView.expressionComposingTextSource = ExpressionComposingTextSource(
            isComposing = { true }, rawInput = { "ji'gou" },
            isAssociate = { false }, candidateText = { "机构" },
        )
        val state = inputView.expressionState()
        inputView.onSettingsMenuClick(SkbMenuMode.AiDoutu)
        assertTrue(state.isVisible)
        assertEquals(ExpressionPanelTab.AI_SYNTHESIS, state.selectedTab)
        assertEquals("机构", state.query)
        assertTrue(state.results.isEmpty())
        assertEquals(15, inputView.findViewById<RecyclerView>(R.id.expression_asset_list).adapter!!.itemCount)
        val pool = ExpressionCatalog.fromAssets(context).synthesisTemplates("机构")
        assertTrue(pool.all { it.id.startsWith("blank-") && it.format == "gif" && it.embeddedText.isNullOrBlank() })
        inputView.onSettingsMenuClick(SkbMenuMode.AiDoutu)
        assertFalse(state.isVisible)
        assertTrue(state.recommendationsPaused)
        assertFalse(state.applyResults(1, state.results))
        inputView.onSettingsMenuClick(SkbMenuMode.AiDoutu)
        assertTrue(state.isVisible)
        assertEquals(ExpressionPanelTab.AI_SYNTHESIS, state.selectedTab)
        state.selectTab(ExpressionPanelTab.EMOJI_SYNTHESIS)
        inputView.onSettingsMenuClick(SkbMenuMode.AiDoutu)
        assertFalse("切换标签后顶部按钮仍须关闭", state.isVisible)
    }

    @Test
    fun `生产AI斗图入口无文字时只显示精确提示且状态不变`() {
        AppPrefs.getInstance().internal.aiStickerEnabled.setValue(false)
        val inputView = realChatInputView()
        val before = inputView.expressionState()
        assertFalse(before.aiStickerEnabled)
        assertNull(before.query)

        inputView.onSettingsMenuClick(SkbMenuMode.AiDoutu)

        val hint = inputView.findViewWithTag<TextView>("expression_usage_hint")
        assertNotNull("空输入应在键盘内显示用途提示", hint)
        assertEquals("AI斗图：根据你输入的文字匹配表情。请先输入文字，再点击“AI斗图”。", hint.text.toString())
        assertEquals(View.VISIBLE, hint.visibility)
        assertNull("不再依赖系统 Toast", ShadowToast.getTextOfLatestToast())
        assertFalse(before.isVisible)
        hint.performClick()
        assertNull(inputView.findViewWithTag<TextView>("expression_usage_hint"))
        val after = inputView.expressionState()
        assertFalse(after.aiStickerEnabled)
        assertNull(after.query)
        assertEquals(ExpressionPanelPresentation.COMPACT, after.presentation)
        assertFalse(AppPrefs.getInstance().internal.aiStickerEnabled.getValue())
    }

    @Test
    @Suppress("DEPRECATION")
    fun `再次点击同一输入框立即关闭用途提示且保留斗图状态`() {
        val inputView = realChatInputView()
        inputView.searchExpressionsManually()
        assertNotNull(inputView.findViewWithTag<TextView>("expression_usage_hint"))
        val state = inputView.expressionState()
        state.beginQuery("保留", 91)

        services.last().onViewClicked(false)

        assertNull(inputView.findViewWithTag<TextView>("expression_usage_hint"))
        assertEquals("保留", state.query)
    }

    @Test
    fun `输入框再次请求键盘立即关闭用途提示`() {
        val inputView = realChatInputView()
        inputView.searchExpressionsManually()
        assertNotNull(inputView.findViewWithTag<TextView>("expression_usage_hint"))

        services.last().onShowInputRequested(android.view.inputmethod.InputMethod.SHOW_EXPLICIT, false)

        assertNull(inputView.findViewWithTag<TextView>("expression_usage_hint"))
    }

    @Test
    fun `输入框选区回调即使位置未变也立即关闭用途提示`() {
        val inputView = realChatInputView()
        inputView.searchExpressionsManually()
        assertNotNull(inputView.findViewWithTag<TextView>("expression_usage_hint"))

        inputView.onUpdateSelection(0, 0, 0, 0, -1)

        assertNull(inputView.findViewWithTag<TextView>("expression_usage_hint"))
    }

    @Test
    fun `同一输入目标重启立即关闭用途提示但不重置查询`() {
        val inputView = realChatInputView()
        val editor = chatEditorInfo()
        val connection = Any()
        inputView.onExpressionInputViewStarted(editor, false, connection)
        inputView.searchExpressionsManually()
        assertNotNull(inputView.findViewWithTag<TextView>("expression_usage_hint"))
        inputView.expressionState().beginQuery("保留", 92)

        inputView.onExpressionInputViewStarted(editor, true, connection)

        assertNull(inputView.findViewWithTag<TextView>("expression_usage_hint"))
        assertEquals("保留", inputView.expressionState().query)
    }

    @Test
    fun `空输入用途提示重复点击不叠加并会自动消失`() {
        val inputView = realChatInputView()
        attachAndLayout(inputView, 2000)
        val root = inputView.rootView
        fun layoutRoot() {
            root.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, 1080, 2000)
        }
        layoutRoot()
        val keyboardHeight = inputView.height
        inputView.searchExpressionsManually()
        layoutRoot()
        assertEquals("提示不应撑高键盘", keyboardHeight, inputView.height)
        val first = inputView.findViewWithTag<TextView>("expression_usage_hint")
        assertNotNull(first)
        assertTrue("提示必须有可见尺寸：hint=${first.width}x${first.height}, parent=${(first.parent as View).width}x${(first.parent as View).height}", first.width > 0 && first.height > 0)
        inputView.searchExpressionsManually()
        assertSame(first, inputView.findViewWithTag<TextView>("expression_usage_hint"))
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(6, TimeUnit.SECONDS)
        assertNull(inputView.findViewWithTag<TextView>("expression_usage_hint"))
    }

    @Test
    fun `输入目标切换和资源释放会清理用途提示`() {
        val inputView = realChatInputView()
        inputView.searchExpressionsManually()
        assertNotNull(inputView.findViewWithTag<TextView>("expression_usage_hint"))
        inputView.onExpressionInputTargetChanged(EditorInfo())
        assertNull(inputView.findViewWithTag<TextView>("expression_usage_hint"))
        inputView.searchExpressionsManually()
        assertNotNull(inputView.findViewWithTag<TextView>("expression_usage_hint"))
        inputView.disposeExpressionResources()
        assertNull(inputView.findViewWithTag<TextView>("expression_usage_hint"))
    }

    @Test
    fun `干嘛未命中时手动AI只显示合成且谢谢命中仍可显示推荐`() {
        val inputView = realChatInputView()
        for (query in listOf("干嘛", "谢谢")) {
            inputView.expressionState().clear()
            inputView.expressionComposingTextSource = ExpressionComposingTextSource(
                isComposing = { true }, rawInput = { "test" }, isAssociate = { false },
                candidateText = { query },
            )
            inputView.onSettingsMenuClick(SkbMenuMode.AiDoutu)
            val state = inputView.expressionState()
            assertEquals(ExpressionPanelTab.AI_SYNTHESIS, state.selectedTab)
            val tab = inputView.findViewById<TextView>(R.id.expression_tab_recommended)
            if (query == "干嘛") {
                assertTrue(state.results.isEmpty())
                assertEquals(View.GONE, tab.visibility)
            } else {
                assertTrue(state.results.isNotEmpty())
                assertTrue(state.results.all { it.type == "prebuilt" })
                assertEquals(View.VISIBLE, tab.visibility)
                tab.performClick()
                assertEquals(ExpressionPanelTab.RECOMMENDED, state.selectedTab)
            }
        }
    }

    @Test
    fun `生产AI斗图入口优先当前组合候选并立即启用现有面板查询`() {
        AppPrefs.getInstance().internal.aiStickerEnabled.setValue(false)
        val inputView = realChatInputView()
        inputView.searchExpressionsManually()
        assertNotNull(inputView.findViewWithTag<TextView>("expression_usage_hint"))
        inputView.expressionComposingTextSource = ExpressionComposingTextSource(
            isComposing = { true },
            rawInput = { "min'ying'qi'ye" },
            isAssociate = { false },
            candidateText = { " 民营企业 " },
        )

        inputView.onSettingsMenuClick(SkbMenuMode.AiDoutu)

        val state = inputView.expressionState()
        assertEquals("民营企业", state.query)
        assertNull(inputView.findViewWithTag<TextView>("expression_usage_hint"))
        assertTrue(state.aiStickerEnabled)
        assertTrue(AppPrefs.getInstance().internal.aiStickerEnabled.getValue())
    }

    @Test
    fun `清输入失败保留斗图候选允许重试`() {
        val inputView = realChatInputView()
        var compositionCleared = false
        inputView.expressionComposingTextSource = ExpressionComposingTextSource(
            isComposing = { true },
            rawInput = { "bo'li'xin" },
            isAssociate = { false },
            candidateText = { "玻璃心" },
            clearComposition = { compositionCleared = true },
        )
        val asset = ExpressionAsset(
            id = "glass-heart",
            type = "prebuilt",
            format = "webp",
            version = "v1",
            fileName = "missing/glass-heart.webp",
            sha256 = "a".repeat(64),
            width = 128,
            height = 128,
        )
        val state = inputView.expressionState().apply {
            beginQuery("玻璃心", 10)
            applyResults(10, listOf(asset))
        }
        val panel = inputView.findViewById<ExpressionPanel>(R.id.expression_panel)
        panel.render(state, ExpressionCatalog.fromAssets(context))

        panel.onAssetClick?.invoke(asset)

        assertTrue(compositionCleared)
        assertEquals("玻璃心", state.query)
        assertEquals(listOf(asset), state.results)
        assertEquals(
            context.getString(R.string.expression_clear_input_failed),
            ShadowToast.getTextOfLatestToast(),
        )
    }

    @Test
    fun `切换输入框清除手动搜索会话和面板旧查询且表情入口仍用自有表情`() {
        val inputView = realChatInputView()
        var activeCompositionCleared = false
        inputView.expressionComposingTextSource = ExpressionComposingTextSource(
            isComposing = { false },
            rawInput = { null },
            isAssociate = { false },
            candidateText = { null },
            clearComposition = { activeCompositionCleared = true },
        )
        inputView.notifyExpressionTextCommitted("旧输入框文字")
        inputView.onSettingsMenuClick(SkbMenuMode.AiDoutu)
        assertEquals("旧输入框文字", inputView.expressionState().query)

        inputView.onExpressionInputTargetChanged(EditorInfo())
        assertNull(InputView::class.java.getDeclaredField("expressionManualQuery").apply {
            isAccessible = true
        }.get(inputView))
        assertTrue(activeCompositionCleared)
        assertNull(inputView.expressionState().query)
        ShadowToast.reset()
        inputView.onSettingsMenuClick(SkbMenuMode.AiDoutu)
        assertEquals(context.getString(R.string.expression_manual_search_missing_text),
            inputView.findViewWithTag<TextView>("expression_usage_hint")?.text?.toString())
        assertNull(inputView.expressionState().query)

        inputView.onSettingsMenuClick(SkbMenuMode.Emojicon)
        val symbol = KeyboardManager.instance.currentContainer as SymbolContainer
        assertEquals(SymbolMode.Emojicon, symbol.getMenuMode())
    }

    @Test
    fun `只有过期候选而无引擎组合态时仍按无文字处理`() {
        val inputView = realChatInputView()
        inputView.expressionComposingTextSource = ExpressionComposingTextSource(
            isComposing = { false },
            rawInput = { "old" },
            isAssociate = { false },
            candidateText = { "过期候选" },
        )

        inputView.onSettingsMenuClick(SkbMenuMode.AiDoutu)

        assertEquals(context.getString(R.string.expression_manual_search_missing_text),
            inputView.findViewWithTag<TextView>("expression_usage_hint")?.text?.toString())
        assertNull(inputView.expressionState().query)
    }

    @Test
    fun `短语内部编辑的候选提交不冒充宿主上屏文字`() {
        val inputView = realChatInputView()
        inputView.expressionComposingTextSource = ExpressionComposingTextSource(
            isComposing = { false },
            rawInput = { null },
            isAssociate = { false },
            candidateText = { null },
        )
        inputView.isAddPhrases = true

        inputView.commitCandidateAndNotify("短语内部文字")
        inputView.onSettingsMenuClick(SkbMenuMode.AiDoutu)

        assertEquals(context.getString(R.string.expression_manual_search_missing_text),
            inputView.findViewWithTag<TextView>("expression_usage_hint")?.text?.toString())
        assertNull(inputView.expressionState().query)
    }

    @Test
    fun `英文26键native组合态可立即搜索当前英文候选`() {
        val inputView = realChatInputView()
        inputView.expressionComposingTextSource = ExpressionComposingTextSource(
            isComposing = { true },
            rawInput = { "hello" },
            isAssociate = { false },
            candidateText = { "Hello" },
        )

        inputView.onSettingsMenuClick(SkbMenuMode.AiDoutu)

        assertEquals("Hello", inputView.expressionState().query)
    }

    @Test
    fun `同一输入目标restarting保留组合和斗图会话`() {
        val inputView = realChatInputView()
        var compositionClearCount = 0
        inputView.expressionComposingTextSource = ExpressionComposingTextSource(
            isComposing = { false }, rawInput = { null }, isAssociate = { false },
            candidateText = { null }, clearComposition = { compositionClearCount += 1 },
        )
        val editor = chatEditorInfo()
        val connection = Any()
        inputView.onExpressionInputViewStarted(editor, restarting = false, connectionIdentity = connection)
        inputView.notifyExpressionTextCommitted("当前会话")
        inputView.onSettingsMenuClick(SkbMenuMode.AiDoutu)
        assertEquals("当前会话", inputView.expressionState().query)

        inputView.onExpressionInputViewStarted(editor, restarting = true, connectionIdentity = connection)

        assertEquals(1, compositionClearCount)
        assertEquals("当前会话", inputView.expressionState().query)
    }

    @Test
    fun `非re restarting即使编辑器相同也清理组合和斗图会话`() {
        val inputView = realChatInputView()
        var compositionClearCount = 0
        inputView.expressionComposingTextSource = ExpressionComposingTextSource(
            isComposing = { false }, rawInput = { null }, isAssociate = { false },
            candidateText = { null }, clearComposition = { compositionClearCount += 1 },
        )
        val editor = chatEditorInfo()
        val connection = Any()
        inputView.onExpressionInputViewStarted(editor, restarting = false, connectionIdentity = connection)
        inputView.notifyExpressionTextCommitted("上一会话")
        inputView.onSettingsMenuClick(SkbMenuMode.AiDoutu)

        inputView.onExpressionInputViewStarted(editor, restarting = false, connectionIdentity = connection)

        assertEquals(2, compositionClearCount)
        assertNull(inputView.expressionState().query)
    }

    @Test
    fun `真实数字键盘成功发送的123累积为斗图查询`() {
        val inputView = realChatInputView()
        val sentKeyCodes = mutableListOf<Int>()
        services.last().hostKeyEventSender = { keyCode ->
            sentKeyCodes += keyCode
            true
        }
        InputModeSwitcher.saveInputMode(InputModeSwitcher.MASK_SKB_LAYOUT_NUMBER) {}

        listOf(KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_3).forEach { keyCode ->
            assertTrue(inputView.processKeyUp(KeyEvent(KeyEvent.ACTION_UP, keyCode)))
        }
        inputView.expressionState().setChatEditor(true)
        inputView.onSettingsMenuClick(SkbMenuMode.AiDoutu)

        assertEquals(listOf(KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_3), sentKeyCodes)
        assertEquals("123", inputView.expressionState().query)
    }

    @Test
    fun `数字键发送失败不记录且非数字键不进入追踪`() {
        val inputView = realChatInputView()
        var numericSendCount = 0
        val service = services.last()
        service.hostKeyEventSender = {
            numericSendCount += 1
            false
        }
        InputModeSwitcher.saveInputMode(InputModeSwitcher.MASK_SKB_LAYOUT_NUMBER) {}

        inputView.processKeyUp(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_1))
        inputView.processKeyUp(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT))
        assertFalse(service.sendNumericKeyEventAndReport(KeyEvent.KEYCODE_DEL))
        assertFalse(service.sendNumericKeyEventAndReport(KeyEvent.KEYCODE_DPAD_LEFT))
        assertFalse(service.sendNumericKeyEventAndReport(KeyEvent.KEYCODE_ENTER))
        inputView.onSettingsMenuClick(SkbMenuMode.AiDoutu)

        assertEquals(2, numericSendCount)
        assertEquals(context.getString(R.string.expression_manual_search_missing_text),
            inputView.findViewWithTag<TextView>("expression_usage_hint")?.text?.toString())
        assertNull(inputView.expressionState().query)
    }

    @Test
    fun `中文分词成功上屏后搜索整句`() {
        val view = realChatInputView()
        listOf("我", "打", "你", "哦").forEach { view.notifyExpressionTextCommitted(it) }
        view.onSettingsMenuClick(SkbMenuMode.AiDoutu)
        assertEquals("我打你哦", view.expressionState().query)
    }

    @Test
    fun `宿主触摸移动光标后不得拼接旧中文片段`() {
        val view = realChatInputView()
        view.notifyExpressionTextCommitted("谢谢")
        view.onExpressionSelectionChanged(0, 0, 2, 2, -1)
        view.onExpressionSelectionChanged(2, 2, 1, 1, -1)
        view.notifyExpressionTextCommitted("啦")
        view.onSettingsMenuClick(SkbMenuMode.AiDoutu)
        assertEquals("啦", view.expressionState().query)
    }

    @Test
    fun `正常提交和组合光标前进保留中文续接而宿主选区清空`() {
        val view = realChatInputView()
        view.notifyExpressionTextCommitted("谢谢")
        view.onExpressionSelectionChanged(0, 0, 2, 2, -1)
        // 新一段拼音组合移动光标，但它还不是已提交文字。
        view.onExpressionSelectionChanged(2, 2, 4, 4, 4)
        view.notifyExpressionTextCommitted("啦")
        view.onSettingsMenuClick(SkbMenuMode.AiDoutu)
        assertEquals("谢谢啦", view.expressionState().query)
        view.onExpressionSelectionChanged(4, 4, 1, 3, -1)
        view.notifyExpressionTextCommitted("你好")
        view.onSettingsMenuClick(SkbMenuMode.AiDoutu)
        assertEquals("你好", view.expressionState().query)
    }

    @Test
    fun `英文和数字成功删除后不会继续拼接旧缓存`() {
        val inputView = realChatInputView()
        val service = services.last()
        service.hostKeyEventSender = { true }
        "hellp".forEach {
            inputView.notifyExpressionTextCommitted(it.toString(), ExpressionCommitKind.INCREMENTAL)
        }

        assertTrue(service.sendEditingKeyEventAndReport(KeyEvent.KEYCODE_DEL))
        inputView.notifyExpressionTextCommitted("o", ExpressionCommitKind.INCREMENTAL)
        inputView.onSettingsMenuClick(SkbMenuMode.AiDoutu)
        assertEquals("o", inputView.expressionState().query)

        inputView.onExpressionInputTargetChanged(chatEditorInfo())
        InputModeSwitcher.saveInputMode(InputModeSwitcher.MASK_SKB_LAYOUT_NUMBER) {}
        listOf(KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_3).forEach { keyCode ->
            inputView.processKeyUp(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
        inputView.processKeyUp(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
        inputView.processKeyUp(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_4))
        inputView.onSettingsMenuClick(SkbMenuMode.AiDoutu)
        assertEquals("4", inputView.expressionState().query)
    }

    @Test
    fun `方向移动后新数字不拼旧串且失败编辑不改缓存`() {
        val inputView = realChatInputView()
        val service = services.last()
        var editSucceeds = true
        service.hostKeyEventSender = { keyCode ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DEL) editSucceeds else true
        }
        InputModeSwitcher.saveInputMode(InputModeSwitcher.MASK_SKB_LAYOUT_NUMBER) {}
        listOf(KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_2).forEach { keyCode ->
            inputView.processKeyUp(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }

        inputView.processKeyUp(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT))
        inputView.processKeyUp(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_3))
        inputView.onSettingsMenuClick(SkbMenuMode.AiDoutu)
        assertEquals("3", inputView.expressionState().query)

        inputView.onExpressionInputTargetChanged(chatEditorInfo())
        inputView.expressionState().setChatEditor(true)
        inputView.processKeyUp(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_1))
        inputView.processKeyUp(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_2))
        editSucceeds = false
        inputView.processKeyUp(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
        inputView.processKeyUp(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_3))
        inputView.onSettingsMenuClick(SkbMenuMode.AiDoutu)
        assertEquals("123", inputView.expressionState().query)
    }

    @Test
    fun `窗口隐藏取消Emoji加载后同组合能够重新发起加载`() {
        val inputView = realChatInputView()
        val panel = inputView.findViewById<ExpressionPanel>(R.id.expression_panel)
        val picker = panel.findViewById<EmojiCombinationPicker>(R.id.expression_emoji_picker)
        var requests = 0
        panel.onEmojiCombinationMissing = { _, _ -> requests++ }
        picker.render(missingEmojiCatalog())
        selectFirstEmojiTwice(picker)
        assertEquals(1, requests)

        inputView.onExpressionWindowHidden()

        picker.render(missingEmojiCatalog())
        selectFirstEmojiTwice(picker)
        assertEquals(2, requests)
        assertFalse(picker.findViewById<View>(R.id.expression_emoji_preview).isEnabled)
    }

    @Test
    fun `窗口隐藏后晚到提交无查询且新会话重新生效`() {
        val inputView = realChatInputView()
        val service = services.last()
        service.hostKeyEventSender = { true }

        inputView.onExpressionWindowHidden()
        assertTrue(service.sendNumericKeyEventAndReport(KeyEvent.KEYCODE_7))
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
        inputView.onSettingsMenuClick(SkbMenuMode.AiDoutu)
        assertEquals(context.getString(R.string.expression_manual_search_missing_text),
            inputView.findViewWithTag<TextView>("expression_usage_hint")?.text?.toString())
        assertNull(inputView.expressionState().query)

        inputView.onExpressionInputViewStarted(chatEditorInfo(), restarting = false, connectionIdentity = Any())
        assertTrue(service.sendNumericKeyEventAndReport(KeyEvent.KEYCODE_8))
        inputView.onSettingsMenuClick(SkbMenuMode.AiDoutu)
        assertEquals("8", inputView.expressionState().query)
    }

    @Test
    fun `新InputView绑定后旧视图销毁不会抢走或收到提交通知`() {
        val service = Robolectric.buildService(ImeService::class.java).create().get()
        services += service
        val oldInputView = service.onCreateInputView() as InputView
        inputViews += oldInputView
        oldInputView.expressionComposingTextSource = ExpressionComposingTextSource(
            isComposing = { false }, rawInput = { null }, isAssociate = { false }, candidateText = { null },
        )
        oldInputView.onExpressionWindowHidden()
        val newInputView = service.onCreateInputView() as InputView
        inputViews += newInputView
        newInputView.expressionComposingTextSource = ExpressionComposingTextSource(
            isComposing = { false }, rawInput = { null }, isAssociate = { false }, candidateText = { null },
        )
        newInputView.onExpressionInputViewStarted(chatEditorInfo(), false, Any())
        oldInputView.disposeExpressionResources()
        service.hostKeyEventSender = { true }

        service.sendNumericKeyEventAndReport(KeyEvent.KEYCODE_9)
        newInputView.onSettingsMenuClick(SkbMenuMode.AiDoutu)
        assertEquals("9", newInputView.expressionState().query)
        ShadowToast.reset()
        oldInputView.onSettingsMenuClick(SkbMenuMode.AiDoutu)
        assertEquals(context.getString(R.string.expression_manual_search_missing_text),
            oldInputView.findViewWithTag<TextView>("expression_usage_hint")?.text?.toString())
    }

    @Test
    fun `发送动作和Enter键成功后英文数字不拼上一条消息`() {
        val inputView = realChatInputView()
        val service = services.last()
        service.hostTextCommitter = { _, _ -> true }
        service.hostEditorActionSender = { true }
        service.hostKeyEventSender = { true }
        service.commitTextAndReport("hello", kind = ExpressionCommitKind.INCREMENTAL)
        YuyanEmojiCompat.setEditorInfo(EditorInfo().apply { imeOptions = EditorInfo.IME_ACTION_SEND })

        service.sendEnterKeyEvent()
        service.commitTextAndReport("world", kind = ExpressionCommitKind.INCREMENTAL)
        inputView.onSettingsMenuClick(SkbMenuMode.AiDoutu)
        assertEquals("world", inputView.expressionState().query)

        inputView.onExpressionInputTargetChanged(chatEditorInfo())
        service.sendNumericKeyEventAndReport(KeyEvent.KEYCODE_1)
        service.sendNumericKeyEventAndReport(KeyEvent.KEYCODE_2)
        YuyanEmojiCompat.setEditorInfo(EditorInfo().apply { imeOptions = EditorInfo.IME_ACTION_NONE })
        service.sendEnterKeyEvent()
        service.sendNumericKeyEventAndReport(KeyEvent.KEYCODE_3)
        inputView.onSettingsMenuClick(SkbMenuMode.AiDoutu)
        assertEquals("3", inputView.expressionState().query)
    }

    @Test
    fun `换行commit只在成功时切断逐字缓存`() {
        val inputView = realChatInputView()
        val service = services.last()
        service.hostTextCommitter = { _, _ -> true }
        service.commitTextAndReport("hello", kind = ExpressionCommitKind.INCREMENTAL)

        assertTrue(service.commitTextAndReport("\n", kind = ExpressionCommitKind.INCREMENTAL))
        service.commitTextAndReport("world", kind = ExpressionCommitKind.INCREMENTAL)
        inputView.onSettingsMenuClick(SkbMenuMode.AiDoutu)
        assertEquals("world", inputView.expressionState().query)

        inputView.onExpressionInputTargetChanged(chatEditorInfo())
        service.commitTextAndReport("hello", kind = ExpressionCommitKind.INCREMENTAL)
        service.hostTextCommitter = { text, _ -> text != "\n" }
        assertFalse(service.commitTextAndReport("\n", kind = ExpressionCommitKind.INCREMENTAL))
        service.commitTextAndReport("world", kind = ExpressionCommitKind.INCREMENTAL)
        inputView.onSettingsMenuClick(SkbMenuMode.AiDoutu)
        assertEquals("helloworld", inputView.expressionState().query)
    }

    @Test
    fun `显式performEditorAction成功清缓存失败则保留`() {
        val inputView = realChatInputView()
        val service = services.last()
        service.hostTextCommitter = { _, _ -> true }
        service.hostEditorActionSender = { true }
        service.commitTextAndReport("hello", kind = ExpressionCommitKind.INCREMENTAL)

        service.performEditorAction(EditorInfo.IME_ACTION_SEND)
        service.commitTextAndReport("world", kind = ExpressionCommitKind.INCREMENTAL)
        inputView.onSettingsMenuClick(SkbMenuMode.AiDoutu)
        assertEquals("world", inputView.expressionState().query)

        inputView.onExpressionInputTargetChanged(chatEditorInfo())
        service.commitTextAndReport("hello", kind = ExpressionCommitKind.INCREMENTAL)
        service.hostEditorActionSender = { false }
        service.performEditorAction(EditorInfo.IME_ACTION_SEND)
        service.commitTextAndReport("world", kind = ExpressionCommitKind.INCREMENTAL)
        inputView.onSettingsMenuClick(SkbMenuMode.AiDoutu)
        assertEquals("helloworld", inputView.expressionState().query)
    }



    @Test
    fun `同一真实InputView卸载重挂后手动和自动斗图搜索重新可用`() {
        val inputView = realChatInputView()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        activity.setContentView(root)
        root.addView(inputView)

        root.removeView(inputView)
        root.addView(inputView)
        inputView.onExpressionInputViewStarted(chatEditorInfo(), restarting = false, connectionIdentity = Any())
        inputView.notifyExpressionTextCommitted("重新连接")
        inputView.onSettingsMenuClick(SkbMenuMode.AiDoutu)

        assertEquals("重新连接", inputView.expressionState().query)
        inputView.notifyExpressionTextCommitted("自动推荐")
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(250, TimeUnit.MILLISECONDS)
        assertEquals("重新连接自动推荐", inputView.expressionState().query)
    }

    @Test
    @Config(minSdk = 33)
    fun `API33及以上展开中的InputView卸载重挂后归一瞬态并可再次展开返回`() {
        assertTrue(Build.VERSION.SDK_INT >= 33)
        AppPrefs.getInstance().internal.aiStickerEnabled.setValue(true)
        val inputView = realChatInputView()
        val connectionIdentity = Any()
        inputView.onExpressionInputViewStarted(chatEditorInfo(), restarting = false, connectionIdentity)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        activity.setContentView(root)
        root.addView(inputView)
        val state = inputView.expressionState()
        val result = ExpressionAsset(
            id = "reattach-result",
            type = "prebuilt",
            format = "webp",
            version = "v1",
            fileName = "reattach.webp",
            sha256 = "f".repeat(64),
            width = 128,
            height = 128,
        )
        state.beginQuery("保留重挂状态", 906)
        state.applyResults(906, listOf(result))
        state.selectTab(ExpressionPanelTab.AI_SYNTHESIS)
        val panel = inputView.findViewById<ExpressionPanel>(R.id.expression_panel)
        panel.render(state, ExpressionCatalog.fromAssets(context))
        val candidates = inputView.findViewById<View>(R.id.candidates_bar)
        val keyboard = inputView.findViewById<View>(R.id.skb_input_keyboard_view)
        val originalVisibility = candidates.visibility to keyboard.visibility
        panel.findViewById<View>(R.id.expression_asset_list).performLongClick()
        assertEquals(ExpressionPanelPresentation.EXPANDED, state.presentation)

        val retryCatalog = missingEmojiCatalog()
        val picker = panel.findViewById<EmojiCombinationPicker>(R.id.expression_emoji_picker)
        var missingRequests = 0
        panel.onEmojiCombinationMissing = { _, _ -> missingRequests += 1 }
        picker.render(retryCatalog)
        selectFirstEmojiTwice(picker)
        assertEquals(1, missingRequests)

        root.removeView(inputView)

        assertEquals(ExpressionPanelPresentation.COMPACT, state.presentation)
        assertEquals(originalVisibility.first, candidates.visibility)
        assertEquals(originalVisibility.second, keyboard.visibility)
        assertEquals("保留重挂状态", state.query)
        assertEquals(listOf(result), state.results)
        assertEquals(ExpressionPanelTab.AI_SYNTHESIS, state.selectedTab)

        root.addView(inputView)
        panel.onEmojiCombinationMissing = { _, _ -> missingRequests += 1 }
        picker.render(retryCatalog)
        selectFirstEmojiTwice(picker)
        assertEquals(2, missingRequests)

        panel.render(state, ExpressionCatalog.fromAssets(context))
        panel.findViewById<View>(R.id.expression_asset_list).performLongClick()
        assertEquals(ExpressionPanelPresentation.EXPANDED, state.presentation)
        assertTrue(inputView.handleImePanelBack())
        assertEquals(ExpressionPanelPresentation.COMPACT, state.presentation)
        assertEquals(originalVisibility.first, candidates.visibility)
        assertEquals(originalVisibility.second, keyboard.visibility)

        val service = services.last()
        service.hostKeyEventSender = { true }
        service.hostTextCommitter = { _, _ -> true }
        inputView.onExpressionInputViewStarted(chatEditorInfo(), restarting = true, connectionIdentity)
        assertSame(
            inputView,
            ImeService::class.java.getDeclaredField("hostTextCommitListenerOwner").run {
                isAccessible = true
                get(service)
            },
        )
        val editListener = ImeService::class.java.getDeclaredField("hostTextEditListener").run {
            isAccessible = true
            get(service)
        }
        assertNotNull(editListener)
        assertTrue(service.sendEditingKeyEventAndReport(KeyEvent.KEYCODE_DEL))
        assertNull(state.query)
        assertTrue(service.commitTextAndReport("重挂后文字", kind = ExpressionCommitKind.INCREMENTAL))
        val manualSearch = InputView::class.java.getDeclaredField("expressionManualSearch").run {
            isAccessible = true
            get(inputView)
        }
        assertEquals(
            "重挂后文字",
            manualSearch.javaClass.getDeclaredField("recentCommittedText").run {
                isAccessible = true
                get(manualSearch)
            },
        )
        assertTrue(state.chatEditor)
        assertNull(inputView.expressionComposingTextSource.currentText(inputView.mSkbCandidatesBarView.getActiveCandNo()))
        inputView.searchExpressionsManually()
        assertEquals("重挂后文字", state.query)
    }

    @Test
    fun `首次零导航后注入非零Insets会立即收紧真实面板高度`() {
        val prefs = AppPrefs.getInstance().internal
        prefs.keyboardModeFloat.setValue(false)
        prefs.fullDisplayKeyboardEnable.setValue(false)
        prefs.keyboardBottomPadding.setValue(40)
        prefs.aiStickerEnabled.setValue(true)
        EnvironmentSingleton.instance.initData(context)
        val inputView = realChatInputView()
        inputView.expressionState().apply {
            beginQuery("布局", 901)
            applyResults(
                901,
                listOf(
                    ExpressionAsset(
                        id = "layout-budget",
                        type = "prebuilt",
                        format = "webp",
                        version = "v1",
                        fileName = "layout.webp",
                        sha256 = "b".repeat(64),
                        width = 128,
                        height = 128,
                    ),
                ),
            )
        }
        inputView.findViewById<ExpressionPanel>(R.id.expression_panel).render(
            inputView.expressionState(),
            ExpressionCatalog.fromAssets(context),
        )
        val available = EnvironmentSingleton.instance.inputAreaHeight + 40 + 130
        attachAndLayout(inputView, available)
        inputView.refreshExpressionLayoutBudget()
        val before = inputView.expressionLayoutBudget
        val beforePanel = inputView.compactExpressionHeight()
        val panel = inputView.findViewById<ExpressionPanel>(R.id.expression_panel)
        val beforePanelLayoutHeight = panel.layoutParams.height

        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.navigationBars(), androidx.core.graphics.Insets.of(0, 0, 0, 160))
            .build()
        ViewCompat.dispatchApplyWindowInsets(inputView, insets)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val after = inputView.expressionLayoutBudget
        val afterPanel = inputView.compactExpressionHeight()
        assertEquals(160, after.navigationInsetBottomPx)
        assertTrue("before=$beforePanel/$before after=$afterPanel/$after", afterPanel < beforePanel)
        assertTrue(panel.layoutParams.height < beforePanelLayoutHeight)
        assertTrue(afterPanel >= 0)
        assertTrue(afterPanel + after.reservedNonPanelHeightPx <= after.availableHeightPx)
        assertTrue(after.reservedNonPanelHeightPx > before.reservedNonPanelHeightPx)
    }

    @Test
    fun `底部留白全面屏栏悬浮拖动条和键盘高度变化全部计入生产预算`() {
        val prefs = AppPrefs.getInstance().internal
        prefs.keyboardModeFloat.setValue(false)
        prefs.fullDisplayKeyboardEnable.setValue(true)
        prefs.keyboardBottomPadding.setValue(36)
        EnvironmentSingleton.instance.initData(context)
        val inputView = realChatInputView()
        attachAndLayout(inputView, EnvironmentSingleton.instance.inputAreaHeight + 500)
        dispatchNavigationInset(inputView, 48)
        inputView.initView(context)
        inputView.refreshExpressionLayoutBudget()

        val full = inputView.expressionLayoutBudget
        assertTrue(full.reservedNonPanelHeightPx >= EnvironmentSingleton.instance.inputAreaHeight + 36 +
            EnvironmentSingleton.instance.heightForFullDisplayBar + 48)
        assertTrue(inputView.compactExpressionHeight() + full.reservedNonPanelHeightPx <= full.availableHeightPx)

        prefs.keyboardModeFloat.setValue(true)
        prefs.keyboardBottomPaddingFloat.setValue(24)
        EnvironmentSingleton.instance.initData(context)
        inputView.initView(context)
        inputView.refreshExpressionLayoutBudget()
        val floating = inputView.expressionLayoutBudget
        assertEquals(
            EnvironmentSingleton.instance.heightForKeyboardMove + 48,
            inputView.findViewById<View>(R.id.iv_keyboard_holder).minimumHeight,
        )
        assertTrue(floating.reservedNonPanelHeightPx >= EnvironmentSingleton.instance.inputAreaHeight +
            EnvironmentSingleton.instance.heightForKeyboardMove + 48 + 24)
        assertEquals(48, floating.navigationInsetBottomPx)
        assertTrue(inputView.compactExpressionHeight() + floating.reservedNonPanelHeightPx <= floating.availableHeightPx)

        val oldReserved = floating.reservedNonPanelHeightPx
        EnvironmentSingleton.instance.keyBoardHeightRatio =
            EnvironmentSingleton.instance.keyBoardHeightRatio + 0.08f
        EnvironmentSingleton.instance.initData(context)
        inputView.initView(context)
        inputView.refreshExpressionLayoutBudget()
        val resized = inputView.expressionLayoutBudget
        assertTrue(resized.reservedNonPanelHeightPx > oldReserved)
        assertTrue(inputView.compactExpressionHeight() >= 0)
        assertTrue(inputView.compactExpressionHeight() + resized.reservedNonPanelHeightPx <= resized.availableHeightPx)
    }

    @Test
    fun `同一生产InputView横竖配置变化后立即重算布局预算`() {
        val inputView = realChatInputView()
        attachAndLayout(inputView, 900)
        inputView.refreshExpressionLayoutBudget()
        val portraitTabHeight = inputView.findViewById<View>(R.id.expression_tab_bar).layoutParams.height
        val portraitContentHeight = inputView.findViewById<View>(R.id.expression_content).layoutParams.height
        val oldReserved = inputView.expressionLayoutBudget.reservedNonPanelHeightPx

        val landscape = Configuration(context.resources.configuration).apply {
            orientation = Configuration.ORIENTATION_LANDSCAPE
        }
        @Suppress("DEPRECATION")
        context.resources.updateConfiguration(landscape, context.resources.displayMetrics)
        EnvironmentSingleton.instance.isLandscape = true
        EnvironmentSingleton.instance.inputAreaHeight += 32
        InputView::class.java.getDeclaredMethod("onConfigurationChanged", Configuration::class.java).apply {
            isAccessible = true
            invoke(inputView, landscape)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(portraitTabHeight, inputView.findViewById<View>(R.id.expression_tab_bar).layoutParams.height)
        assertTrue(portraitTabHeight >= (32 * context.resources.displayMetrics.density).roundToInt())
        assertTrue(inputView.findViewById<View>(R.id.expression_content).layoutParams.height < portraitContentHeight)
        assertTrue(inputView.expressionLayoutBudget.reservedNonPanelHeightPx >= oldReserved + 32)
        assertTrue(inputView.compactExpressionHeight() + inputView.expressionLayoutBudget.reservedNonPanelHeightPx <=
            inputView.expressionLayoutBudget.availableHeightPx)
    }

    @Test
    fun `WRAP_CONTENT宿主连续十轮空态零高度且有结果面板稳定恢复`() {
        AppPrefs.getInstance().internal.aiStickerEnabled.setValue(true)
        EnvironmentSingleton.instance.initData(context)
        val inputView = realChatInputView()
        val host = FrameLayout(context).apply {
            addView(
                inputView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        val panel = inputView.findViewById<ExpressionPanel>(R.id.expression_panel)
        val tool = inputView.findViewById<View>(R.id.expression_enable)
        val minimumTouch = (32f * context.resources.displayMetrics.density).toInt()

        val toolHeights = repeatWrapLayouts(host, inputView, panel)

        assertEquals(1, toolHeights.toSet().size)
        assertEquals(0, toolHeights.first())
        assertEquals(View.GONE, tool.visibility)
        assertTrue(tool.minimumHeight >= minimumTouch)
        val stableViewport = inputView.expressionLayoutBudget.availableHeightPx

        inputView.expressionState().apply {
            beginQuery("恢复", 902)
            applyResults(
                902,
                listOf(
                    ExpressionAsset(
                        id = "wrap-result",
                        type = "prebuilt",
                        format = "webp",
                        version = "v1",
                        fileName = "wrap.webp",
                        sha256 = "c".repeat(64),
                        width = 128,
                        height = 128,
                    ),
                ),
            )
        }
        panel.render(inputView.expressionState(), ExpressionCatalog.fromAssets(context))
        val resultHeights = repeatWrapLayouts(host, inputView, panel)

        assertEquals(1, resultHeights.toSet().size)
        assertTrue(resultHeights.first() > toolHeights.first())
        assertEquals(stableViewport, inputView.expressionLayoutBudget.availableHeightPx)
        assertTrue(resultHeights.first() + inputView.expressionLayoutBudget.reservedNonPanelHeightPx <= stableViewport)
    }

    @Test
    @Config(sdk = [29])
    fun `SDK29动态系统栏Insets到达后刷新稳定视口`() {
        val inputView = realChatInputView()
        val before = inputView.expressionLayoutBudget
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), androidx.core.graphics.Insets.of(0, 20, 0, 0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), androidx.core.graphics.Insets.of(0, 0, 0, 50))
            .setInsets(WindowInsetsCompat.Type.displayCutout(), androidx.core.graphics.Insets.of(0, 30, 0, 70))
            .build()

        ViewCompat.dispatchApplyWindowInsets(inputView, insets)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val after = inputView.expressionLayoutBudget
        val realBounds = before.availableHeightPx + before.topObstructionPx + before.bottomExtraObstructionPx
        // Robolectric 29 的 Compat Builder 不构造平台 DisplayCutout；刘海合并由纯函数测试覆盖。
        assertEquals(20, after.topObstructionPx)
        assertEquals(0, after.bottomExtraObstructionPx)
        assertEquals(realBounds - 20, after.availableHeightPx)
        assertEquals(50, after.navigationInsetBottomPx)
    }

    @Test
    fun `导航栏显隐时刘海与holder总遮挡恒定且面板不跳高`() {
        AppPrefs.getInstance().internal.aiStickerEnabled.setValue(true)
        val inputView = realChatInputView()
        attachAndLayout(inputView, availableHeight = 2000)
        val state = inputView.expressionState()
        state.beginQuery("稳定面板", 905)
        state.applyResults(
            905,
            listOf(
                ExpressionAsset(
                    id = "stable-cutout",
                    type = "prebuilt",
                    format = "webp",
                    version = "v1",
                    fileName = "stable.webp",
                    sha256 = "e".repeat(64),
                    width = 128,
                    height = 128,
                ),
            ),
        )
        val panel = inputView.findViewById<ExpressionPanel>(R.id.expression_panel)
        panel.render(state, ExpressionCatalog.fromAssets(context))

        fun systemInsets(navigationVisible: Boolean): WindowInsetsCompat {
            val navigation = WindowInsetsCompat.Type.navigationBars()
            val cutout = WindowInsetsCompat.Type.displayCutout()
            return WindowInsetsCompat.Builder()
                .setInsets(
                    navigation,
                    androidx.core.graphics.Insets.of(0, 0, 0, if (navigationVisible) 50 else 0),
                )
                .setInsetsIgnoringVisibility(
                    navigation,
                    androidx.core.graphics.Insets.of(0, 0, 0, 50),
                )
                .setVisible(navigation, navigationVisible)
                .setInsets(cutout, androidx.core.graphics.Insets.of(0, 0, 0, 70))
                .setInsetsIgnoringVisibility(cutout, androidx.core.graphics.Insets.of(0, 0, 0, 70))
                .build()
        }

        fun settleLayout() {
            inputView.rootView.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY),
            )
            inputView.rootView.layout(0, 0, 1080, 2000)
            inputView.refreshExpressionLayoutBudget()
        }

        ViewCompat.dispatchApplyWindowInsets(inputView, systemInsets(navigationVisible = true))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        settleLayout()
        val visibleBudget = inputView.expressionLayoutBudget
        val visiblePanelHeight = panel.layoutParams.height

        ViewCompat.dispatchApplyWindowInsets(inputView, systemInsets(navigationVisible = false))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        settleLayout()
        val hiddenBudget = inputView.expressionLayoutBudget

        assertEquals(50, visibleBudget.navigationInsetBottomPx)
        assertEquals(20, visibleBudget.bottomExtraObstructionPx)
        assertEquals(0, hiddenBudget.navigationInsetBottomPx)
        assertEquals(70, hiddenBudget.bottomExtraObstructionPx)
        assertEquals(
            visibleBudget.navigationInsetBottomPx + visibleBudget.bottomExtraObstructionPx,
            hiddenBudget.navigationInsetBottomPx + hiddenBudget.bottomExtraObstructionPx,
        )
        assertEquals(
            visibleBudget.reservedNonPanelHeightPx - visibleBudget.navigationInsetBottomPx,
            hiddenBudget.reservedNonPanelHeightPx - hiddenBudget.navigationInsetBottomPx,
        )
        assertEquals(
            visibleBudget.availableHeightPx - visibleBudget.reservedNonPanelHeightPx,
            hiddenBudget.availableHeightPx - hiddenBudget.reservedNonPanelHeightPx,
        )
        assertEquals(visiblePanelHeight, panel.layoutParams.height)
        assertTrue(visiblePanelHeight + hiddenBudget.reservedNonPanelHeightPx <= hiddenBudget.availableHeightPx)
    }

    @Test
    fun `极端高度有结果时AI工具行可关闭并恢复原查询结果标签且返回仍先折叠`() {
        AppPrefs.getInstance().internal.aiStickerEnabled.setValue(true)
        val inputView = realChatInputView()
        val state = inputView.expressionState()
        val result = ExpressionAsset(
            id = "toggle-result",
            type = "prebuilt",
            format = "webp",
            version = "v1",
            fileName = "toggle.webp",
            sha256 = "d".repeat(64),
            width = 128,
            height = 128,
        )
        state.beginQuery("保留文字", 903)
        state.applyResults(903, listOf(result))
        state.selectTab(com.yuyan.imemodule.expression.ExpressionPanelTab.AI_SYNTHESIS)
        val panel = inputView.findViewById<ExpressionPanel>(R.id.expression_panel)
        panel.setAvailableLayoutHeight(availableHeightPx = 610, reservedKeyboardHeightPx = 600)
        panel.render(state, ExpressionCatalog.fromAssets(context))
        val toggle = inputView.findViewById<View>(R.id.expression_enable)
        assertEquals(context.getString(R.string.expression_tool_hide_recommendations), toggle.contentDescription)

        toggle.performClick()

        assertFalse(state.isRecommendationVisible)
        assertEquals("保留文字", state.query)
        assertEquals(listOf(result), state.results)
        assertEquals(com.yuyan.imemodule.expression.ExpressionPanelTab.AI_SYNTHESIS, state.selectedTab)
        assertEquals(context.getString(R.string.expression_tool_restore_recommendations), toggle.contentDescription)

        toggle.performClick()

        assertTrue(state.isRecommendationVisible)
        assertEquals("保留文字", state.query)
        assertEquals(listOf(result), state.results)
        assertEquals(com.yuyan.imemodule.expression.ExpressionPanelTab.AI_SYNTHESIS, state.selectedTab)
        panel.findViewById<View>(R.id.expression_asset_list).performLongClick()
        assertEquals(ExpressionPanelPresentation.EXPANDED, state.presentation)
        assertTrue(inputView.handleImePanelBack())
        assertEquals(ExpressionPanelPresentation.COMPACT, state.presentation)

        state.beginQuery("暂无结果", 904)
        panel.render(state, ExpressionCatalog.fromAssets(context))
        toggle.performClick()
        assertEquals("暂无结果", state.query)
        assertTrue(state.results.isEmpty())
    }

    @Test
    fun `生产面板关闭恢复保留查询结果且返回先折叠展开态`() {
        val inputView = realChatInputView()
        val state = inputView.expressionState()
        val asset = ExpressionAsset(
            id = "enterprise",
            type = "prebuilt",
            format = "webp",
            version = "v1",
            fileName = "templates/hello.webp",
            sha256 = "a".repeat(64),
            width = 512,
            height = 512,
        )
        state.beginQuery("民营企业", 77)
        state.applyResults(77, listOf(asset))
        val panel = inputView.findViewById<ExpressionPanel>(R.id.expression_panel)
        panel.render(state, ExpressionCatalog.fromAssets(context))

        panel.findViewById<View>(R.id.expression_close).performClick()
        assertFalse(state.isRecommendationVisible)
        assertEquals("民营企业", state.query)
        assertEquals(listOf(asset), state.results)

        inputView.findViewById<View>(R.id.expression_enable).performClick()
        assertTrue(state.isRecommendationVisible)
        panel.findViewById<View>(R.id.expression_asset_list).performLongClick()
        assertEquals(ExpressionPanelPresentation.EXPANDED, state.presentation)

        assertTrue(inputView.handleImePanelBack())
        assertEquals(ExpressionPanelPresentation.COMPACT, state.presentation)
        assertEquals("民营企业", state.query)
        assertEquals(listOf(asset), state.results)
        assertFalse(inputView.handleExpressionBack())
    }

    @Test
    fun `关闭推荐后普通输入零搜索而AI按钮恢复并只发起一次新请求`() {
        val inputView = realChatInputView()
        val state = inputView.expressionState()
        val panel = inputView.findViewById<ExpressionPanel>(R.id.expression_panel)
        state.beginQuery("旧隐藏结果", 70)
        state.applyResults(70, listOf(ExpressionAsset(
            id = "old-hidden",
            type = "prebuilt",
            format = "webp",
            version = "v1",
            fileName = "templates/old.webp",
            sha256 = "e".repeat(64),
            width = 128,
            height = 128,
        )))
        panel.render(state, ExpressionCatalog.fromAssets(context))
        panel.findViewById<View>(R.id.expression_close).performClick()
        val requestIdBeforeTyping = inputView.expressionRequestId()

        inputView.notifyExpressionTextCommitted("关闭后的新输入")
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)

        assertEquals(requestIdBeforeTyping, inputView.expressionRequestId())
        assertEquals("旧隐藏结果", state.query)
        assertEquals(listOf("old-hidden"), state.results.map { it.id })
        assertFalse(state.isRecommendationVisible)

        inputView.searchExpressionsManually()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(requestIdBeforeTyping + 1, inputView.expressionRequestId())
        assertEquals("关闭后的新输入", state.query)
        assertFalse(state.recommendationsPaused)
    }

    @Test
    @Config(shadows = [LocalServerConfigShadow::class])
    fun `关闭推荐后卸载重挂零自动网络且AI按钮本地打开模板池`() {
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/api/v1/mobile/expressions/versions" ->
                        MockResponse().setBody("""{"version":"${ExpressionCatalog.fromAssets(context).document.version}"}""")
                    request.path?.startsWith("/api/v1/mobile/expressions/recommend") == true ->
                        MockResponse().setBody("""{"results":[]}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            start()
        }
        fun pollRequest(timeoutMs: Long): RecordedRequest? {
            val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            do {
                // IO priming/HTTP回调需切回Main；阻塞主线程takeRequest会产生假红或假绿。
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                server.takeRequest(10, TimeUnit.MILLISECONDS)?.let { return it }
            } while (System.nanoTime() < end)
            return null
        }
        try {
            LocalServerConfigShadow.url = server.url("/").toString().trimEnd('/')
            assertEquals(LocalServerConfigShadow.url, ServerConfig.baseUrl)
            AppPrefs.getInstance().internal.aiStickerEnabled.setValue(true)
            val inputView = realChatInputView()
            assertNull("仅构造InputView不得联网", pollRequest(300))
            inputView.onWindowShown()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals("/api/v1/mobile/expressions/versions", pollRequest(2000)?.path)
            assertNull("版本未变不拉目录或GIF", pollRequest(300))

            val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
            val root = FrameLayout(activity)
            activity.setContentView(root)
            root.addView(inputView)
            val state = inputView.expressionState()
            val oldResult = ExpressionAsset(
                id = "old-before-reattach",
                type = "prebuilt",
                format = "webp",
                version = "v1",
                fileName = "templates/old-before-reattach.webp",
                sha256 = "c".repeat(64),
                width = 128,
                height = 128,
            )
            state.beginQuery("重挂前旧结果", 92)
            state.applyResults(92, listOf(oldResult))
            val panel = inputView.findViewById<ExpressionPanel>(R.id.expression_panel)
            panel.render(state, ExpressionCatalog.fromAssets(context))
            panel.findViewById<View>(R.id.expression_close).performClick()
            assertTrue(state.recommendationsPaused)

            inputView.searchExpressionsManually()
            assertEquals(ExpressionPanelTab.AI_SYNTHESIS, state.selectedTab)
            assertEquals(15, panel.findViewById<RecyclerView>(R.id.expression_asset_list).adapter!!.itemCount)
            assertNull("手动选模板不得强制刷新目录", pollRequest(300))
            panel.findViewById<View>(R.id.expression_close).performClick()
            assertTrue(state.recommendationsPaused)

            root.removeView(inputView)
            root.addView(inputView)

            assertNull("重挂不得自动刷新目录", pollRequest(500))
            val requestIdBeforeManualSearch = inputView.expressionRequestId()
            inputView.notifyExpressionTextCommitted("重挂后手动搜索")
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
            assertNull("关闭期间普通输入不得联网", pollRequest(300))

            inputView.searchExpressionsManually()
            assertEquals(requestIdBeforeManualSearch + 1, inputView.expressionRequestId())
            assertFalse(inputView.expressionState().recommendationsPaused)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(ExpressionPanelTab.AI_SYNTHESIS, inputView.expressionState().selectedTab)
            assertTrue(inputView.expressionState().results.isEmpty())
            assertEquals(15, panel.findViewById<RecyclerView>(R.id.expression_asset_list).adapter!!.itemCount)
            assertNull("手动模板池不等待或发起关键词推荐请求", pollRequest(500))

            inputView.onExpressionWindowHidden()
            inputView.onWindowShown()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals("真实隐藏后再打开需重新检查轻量版本", "/api/v1/mobile/expressions/versions",
                pollRequest(2000)?.path)
            assertNull("第二次打开版本未变仍不拉目录或GIF", pollRequest(300))
        } finally {
            server.shutdown()
            LocalServerConfigShadow.url = ""
        }
    }

    @Test
    fun `空文字AI按钮不会恢复已关闭推荐或启动请求`() {
        val inputView = realChatInputView()
        val state = inputView.expressionState()
        state.beginQuery("保留", 80)
        state.hideRecommendations()
        // 清除手动搜索 fallback，但保留面板旧 query；再清 state query 模拟无有效文字。
        InputView::class.java.getDeclaredField("expressionManualSearch").run {
            isAccessible = true
            get(inputView) as com.yuyan.imemodule.expression.ExpressionManualSearch
        }.resetSession()
        state.clear()
        state.hideRecommendations()
        val before = inputView.expressionRequestId()

        inputView.searchExpressionsManually()

        assertEquals(before, inputView.expressionRequestId())
        assertTrue(state.recommendationsPaused)
        assertEquals(
            context.getString(R.string.expression_manual_search_missing_text),
            inputView.findViewWithTag<TextView>("expression_usage_hint")?.text?.toString(),
        )
    }

    @Test
    fun `手写底栏设置键在真实InputView打开键盘主题快捷面板`() {
        val inputView = realChatInputView()

        inputView.responseKeyEvent(
            SoftKey(InputModeSwitcher.USER_KEYCODE_QUICK_SETTINGS, context.getString(R.string.quick_keyboard_theme)),
        )

        val settings = KeyboardManager.instance.currentContainer as SettingsContainer
        assertTrue(settings.isQuickSettingsVisible)
        settings.findViewWithTag<View>("quick_tab_theme").performClick()
        assertNotNull(settings.findViewWithTag<View>("quick_theme_SogouDefault"))
    }

    @Test
    fun `AI合成等待期间保留卡片和查询只发送一次成功才关闭`() = verifyPendingPreparation()

    @Test
    fun `AI准备失败恢复卡片可重试`() = verifyPendingPreparation(fail = true)

    @Test
    fun `AI准备完成但仅保存相册时保留输入和卡片`() = verifyPendingPreparation(save = true)

    @Test
    fun `真实编辑取消准备且旧finally不覆盖新查询`() = verifyPendingPreparation(cancel = "edit")

    @Test
    fun `切换输入目标取消准备不向错误目标发送`() = verifyPendingPreparation(cancel = "target")

    @Test
    fun `卸载取消准备复位忙状态`() = verifyPendingPreparation(cancel = "detach")

    @Test
    fun `关闭推荐取消准备复位忙状态`() = verifyPendingPreparation(cancel = "close")

    @Test
    fun `真实新提交立即取消准备不等防抖`() = verifyPendingPreparation(cancel = "commit")

    @Test
    fun `发送成功后同词再次提交启动新查询`() = verifyPendingPreparation(repeatQuery = true)

    @Test
    fun `准备期间同词再次提交取消旧准备并启动新查询`() = verifyPendingPreparation(cancel = "sameCommit")

    @Test
    fun `微信原文件交接清除忙状态并提示不冒充发送成功`() = verifyPendingPreparation(share = true)

    @Test
    fun `微信原文件交接后同词再次提交启动新查询`() = verifyPendingPreparation(share = true, repeatQuery = true)

    @Test
    fun `AI准备超时恢复卡片且旧任务不能迟到发送`() = verifyPendingPreparation(timeout = true)

    @Test
    fun `准备后编辑器文字已变化不能清掉新输入`() = verifyPendingPreparation(changedText = true)

    @Test
    fun `准备后无法读取完整输入时不自动清空`() = verifyPendingPreparation(readableInput = false)

    private fun verifyPendingPreparation(fail: Boolean = false, save: Boolean = false, cancel: String? = null, repeatQuery: Boolean = false, share: Boolean = false, timeout: Boolean = false, changedText: Boolean = false, readableInput: Boolean = true) {
        val inputView = realChatInputView()
        var clearCalls = 0
        var inputText = "谢谢"
        val connection = object : android.view.inputmethod.BaseInputConnection(View(context), true) {
            override fun getExtractedText(request: android.view.inputmethod.ExtractedTextRequest?, flags: Int): android.view.inputmethod.ExtractedText? =
                if (!readableInput) null else android.view.inputmethod.ExtractedText().apply {
                    text = inputText; startOffset = 0; partialStartOffset = -1; partialEndOffset = -1
                }
            override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? = if (readableInput) inputText else null
            override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? = if (readableInput) "" else null
            override fun getSelectedText(flags: Int): CharSequence? = null
            override fun performContextMenuAction(id: Int) = true
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (text.isNullOrEmpty()) clearCalls++
                return true
            }
        }
        org.robolectric.util.ReflectionHelpers.setField(services.last(), "mStartedInputConnection", connection)
        val coordinator = InputView::class.java.getDeclaredField("expressionQueryCoordinator").apply { isAccessible = true }.get(inputView)
        coordinator.javaClass.getDeclaredField("currentQuery").apply { isAccessible = true; set(coordinator, "谢谢") }
        val asset = ExpressionCatalog.fromAssets(context).document.templates.first { it.type == "synthesis-template" }
        val state = inputView.expressionState().apply {
            beginQuery("谢谢", 980)
            applyResults(980, listOf(asset))
            selectTab(ExpressionPanelTab.AI_SYNTHESIS)
        }
        val panel = inputView.findViewById<ExpressionPanel>(R.id.expression_panel)
        panel.render(state, ExpressionCatalog.fromAssets(context))
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        var preparedQuery: String? = null
        var prepareCount = 0
        var sent = 0
        val prepared = com.yuyan.imemodule.expression.send.PreparedExpression(java.io.File(context.cacheDir, "prepared.gif"), "image/gif")
        val flow = com.yuyan.imemodule.expression.send.ExpressionFlowController(
            com.yuyan.imemodule.expression.send.ExpressionSendController {
                assertSame(prepared, it)
                sent++
                if (save) com.yuyan.imemodule.expression.send.ExpressionSendResult.UnsupportedTarget
                else if (share) com.yuyan.imemodule.expression.send.ExpressionSendResult.WechatSubmitted
                else com.yuyan.imemodule.expression.send.ExpressionSendResult.Sent
            },
            prepareAsset = { _, query ->
                prepareCount++; preparedQuery = query; release.await()
                if (fail && prepareCount == 1) error("合成失败，请重试")
                prepared
            },
            prepareCombination = { error("unexpected emoji") },
            fallback = { _, result -> if (save) com.yuyan.imemodule.expression.send.ExpressionSendResult.SavedToGallery else result },
        )
        InputView::class.java.getDeclaredField("expressionFlow").apply { isAccessible = true; set(inputView, flow) }
        panel.onAssetClick?.invoke(asset)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals("准备完成前不能删除输入", 0, clearCalls)
        assertEquals("谢谢", state.query)
        assertEquals("谢谢", preparedQuery)
        assertEquals(ExpressionPanelTab.AI_SYNTHESIS, state.selectedTab)
        assertEquals(View.VISIBLE, panel.visibility)
        assertEquals(View.VISIBLE, panel.findViewById<View>(R.id.expression_preparing_overlay)?.visibility)
        panel.onAssetClick?.invoke(asset)
        assertEquals(1, prepareCount)
        assertEquals(0, sent)
        // 准备期间迟到的选区回调不能取消素材准备。
        inputView.onExpressionSelectionChanged(2, 2, 0, 0, -1)
        assertTrue(state.isPreparing)
        // 普通空候选不能取消正在准备的任务。
        DecodingInfo.candidatesLiveData.value = emptyList()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(state.isPreparing)
        if (timeout) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(31, TimeUnit.SECONDS)
            assertFalse(state.isPreparing)
            assertEquals(0, sent)
            assertEquals("谢谢", state.query)
            assertEquals("准备图片超时，请重试", ShadowToast.getTextOfLatestToast())
            assertEquals(View.GONE, panel.findViewById<View>(R.id.expression_preparing_overlay).visibility)
            release.complete(Unit)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, sent)
            panel.onAssetClick?.invoke(asset)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, sent)
            assertFalse(state.isPreparing)
            return
        }
        if (cancel != null) {
            when (cancel) {
                "commit" -> inputView.notifyExpressionTextCommitted("新文字")
                "sameCommit" -> inputView.notifyExpressionTextCommitted("谢谢")
                "edit" -> InputView::class.java.getDeclaredMethod("notifyExpressionTextEdited").apply { isAccessible = true }.invoke(inputView)
                "target" -> inputView.onExpressionInputTargetChanged(chatEditorInfo().apply { fieldId++ })
                "detach" -> inputView.disposeExpressionResources()
                "close" -> panel.onRecommendationVisibilityChange?.invoke(false)
            }
            assertFalse(state.isPreparing)
            if (cancel == "sameCommit") {
                val requestBefore = inputView.expressionRequestId()
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
                assertEquals(requestBefore + 1, inputView.expressionRequestId())
            }
            val current = inputView.expressionState()
            current.beginQuery("新查询", 981)
            current.applyResults(981, listOf(asset))
            release.complete(Unit)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, sent)
            assertEquals("新查询", current.query)
            assertFalse(current.isPreparing)
            return
        }
        if (changedText) inputText = "后来输入"
        release.complete(Unit)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        if (fail) {
            assertEquals(0, sent)
            assertFalse(state.isPreparing)
            assertEquals("谢谢", state.query)
            assertEquals(View.GONE, panel.findViewById<View>(R.id.expression_preparing_overlay).visibility)
            assertEquals("合成失败，请重试", ShadowToast.getTextOfLatestToast())
            panel.onAssetClick?.invoke(asset)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(2, prepareCount)
        }
        assertEquals(1, sent)
        assertFalse(state.isPreparing)
        if (share) assertEquals("已交给当前微信会话，请确认动图是否发出", ShadowToast.getTextOfLatestToast())
        if (save || share) {
            assertEquals("未确认发送不能删除输入", 0, clearCalls)
            assertEquals("谢谢", state.query)
            assertEquals(View.VISIBLE, panel.visibility)
        } else {
            assertEquals(if (changedText || !readableInput) 0 else 1, clearCalls)
            assertNull(state.query)
            assertEquals(View.GONE, panel.visibility)
        }
        if (repeatQuery) {
            val requestBefore = inputView.expressionRequestId()
            inputView.notifyExpressionTextCommitted("谢谢")
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
            assertEquals(requestBefore + 1, inputView.expressionRequestId())
        }
    }

    private fun realChatInputView(): InputView {
        val service = Robolectric.buildService(ImeService::class.java).create().get()
        services += service
        val inputView = service.onCreateInputView() as InputView
        inputViews += inputView
        inputView.expressionState().setChatEditor(true)
        inputView.expressionComposingTextSource = ExpressionComposingTextSource(
            isComposing = { false },
            rawInput = { null },
            isAssociate = { false },
            candidateText = { null },
        )
        return inputView
    }

    private fun attachAndLayout(inputView: InputView, availableHeight: Int) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        activity.setContentView(root)
        root.addView(inputView)
        activity.window.decorView.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(availableHeight, View.MeasureSpec.EXACTLY),
        )
        activity.window.decorView.layout(0, 0, 1080, availableHeight)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    private fun dispatchNavigationInset(inputView: InputView, bottom: Int) {
        ViewCompat.dispatchApplyWindowInsets(
            inputView,
            WindowInsetsCompat.Builder()
                .setInsets(
                    WindowInsetsCompat.Type.navigationBars(),
                    androidx.core.graphics.Insets.of(0, 0, 0, bottom),
                )
                .build(),
        )
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    private fun repeatWrapLayouts(
        host: FrameLayout,
        inputView: InputView,
        panel: ExpressionPanel,
    ): List<Int> = buildList {
        repeat(10) {
            host.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.AT_MOST),
            )
            host.layout(0, 0, host.measuredWidth, host.measuredHeight)
            inputView.refreshExpressionLayoutBudget()
            add(panel.layoutParams.height)
        }
    }

    private fun InputView.compactExpressionHeight(): Int =
        findViewById<View>(R.id.expression_tab_bar).layoutParams.height +
            findViewById<View>(R.id.expression_content).layoutParams.height +
            findViewById<View>(R.id.expression_tool_row).layoutParams.height

    private fun InputView.expressionState(): ExpressionPanelState =
        InputView::class.java.getDeclaredField("expressionPanelState").let { field ->
            field.isAccessible = true
            field.get(this) as ExpressionPanelState
        }

    private fun InputView.expressionRequestId(): Long =
        InputView::class.java.getDeclaredField("expressionRequestId").let { field ->
            field.isAccessible = true
            field.getLong(this)
        }

    @Suppress("UNCHECKED_CAST")
    private fun selectFirstEmojiTwice(picker: EmojiCombinationPicker) {
        val list = picker.findViewById<RecyclerView>(R.id.expression_emoji_list)
        val adapter = list.adapter as RecyclerView.Adapter<RecyclerView.ViewHolder>
        repeat(2) {
            val holder = adapter.createViewHolder(list, adapter.getItemViewType(0))
            adapter.bindViewHolder(holder, 0)
            holder.itemView.performClick()
        }
    }

    private fun missingEmojiCatalog(): ExpressionCatalog {
        val base = EmojiBase(
            id = "retry",
            name = "重试",
            fileName = "missing/retry-base.webp",
            sha256 = "1".repeat(64),
            version = "v1",
            width = 128,
            height = 128,
            sortOrder = 0,
        )
        return ExpressionCatalog(
            ExpressionCatalogDocument(
                version = "v1",
                templates = emptyList(),
                emojiBases = listOf(base),
                emojiCombinations = listOf(
                    EmojiCombination(
                        key = "retry__retry",
                        firstId = "retry",
                        secondId = "retry",
                        fileName = "missing/retry.webp",
                        sha256 = "2".repeat(64),
                        version = "v1",
                        width = 128,
                        height = 128,
                        url = "/missing/retry.webp",
                    ),
                ),
            ),
        )
    }

    private fun chatEditorInfo() = EditorInfo().apply {
        extras = android.os.Bundle().apply { putBoolean("IS_CHAT_EDITOR", true) }
        androidx.core.view.inputmethod.EditorInfoCompat.setContentMimeTypes(this, arrayOf("image/*"))
        packageName = "com.tencent.mm"
        fieldId = 9
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        imeOptions = EditorInfo.IME_ACTION_SEND
    }
}
