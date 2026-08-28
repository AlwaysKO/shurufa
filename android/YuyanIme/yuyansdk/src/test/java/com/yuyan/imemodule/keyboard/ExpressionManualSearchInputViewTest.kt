package com.yuyan.imemodule.keyboard

import android.content.Context
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.data.emojicon.YuyanEmojiCompat
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.expression.ExpressionPanelPresentation
import com.yuyan.imemodule.expression.ExpressionPanelState
import com.yuyan.imemodule.expression.ExpressionComposingTextSource
import com.yuyan.imemodule.expression.ExpressionCommitKind
import com.yuyan.imemodule.keyboard.container.SymbolContainer
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowToast
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class ExpressionManualSearchInputViewTest {
    private lateinit var context: Context
    private val services = mutableListOf<ImeService>()
    private val inputViews = mutableListOf<InputView>()
    private var originalAiStickerEnabled = false

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Launcher::class.java.getDeclaredField("context").apply {
            isAccessible = true
            set(Launcher.instance, context)
        }
        AppPrefs.init(PreferenceManager.getDefaultSharedPreferences(context))
        originalAiStickerEnabled = AppPrefs.getInstance().internal.aiStickerEnabled.getValue()
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
        DecodingInfo.candidatesLiveData.value = emptyList()
        DecodingInfo.isAssociate = false
    }

    @Test
    fun `生产AI斗图入口无文字时只显示精确提示且状态不变`() {
        AppPrefs.getInstance().internal.aiStickerEnabled.setValue(false)
        val inputView = realChatInputView()
        val before = inputView.expressionState()
        assertFalse(before.aiStickerEnabled)
        assertNull(before.query)

        inputView.onSettingsMenuClick(SkbMenuMode.AiDoutu)

        assertEquals("请先输入文字，再点击搜索按钮", ShadowToast.getTextOfLatestToast())
        val after = inputView.expressionState()
        assertFalse(after.aiStickerEnabled)
        assertNull(after.query)
        assertEquals(ExpressionPanelPresentation.COMPACT, after.presentation)
        assertFalse(AppPrefs.getInstance().internal.aiStickerEnabled.getValue())
    }

    @Test
    fun `生产AI斗图入口优先当前组合候选并立即启用现有面板查询`() {
        AppPrefs.getInstance().internal.aiStickerEnabled.setValue(false)
        val inputView = realChatInputView()
        inputView.expressionComposingTextSource = ExpressionComposingTextSource(
            isComposing = { true },
            rawInput = { "min'ying'qi'ye" },
            isAssociate = { false },
            candidateText = { " 民营企业 " },
        )

        inputView.onSettingsMenuClick(SkbMenuMode.AiDoutu)

        val state = inputView.expressionState()
        assertEquals("民营企业", state.query)
        assertTrue(state.aiStickerEnabled)
        assertTrue(AppPrefs.getInstance().internal.aiStickerEnabled.getValue())
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
        assertTrue(activeCompositionCleared)
        assertNull(inputView.expressionState().query)
        ShadowToast.reset()
        inputView.onSettingsMenuClick(SkbMenuMode.AiDoutu)
        assertEquals("请先输入文字，再点击搜索按钮", ShadowToast.getTextOfLatestToast())
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

        assertEquals("请先输入文字，再点击搜索按钮", ShadowToast.getTextOfLatestToast())
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

        assertEquals("请先输入文字，再点击搜索按钮", ShadowToast.getTextOfLatestToast())
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
        assertEquals("请先输入文字，再点击搜索按钮", ShadowToast.getTextOfLatestToast())
        assertNull(inputView.expressionState().query)
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
    fun `窗口隐藏后晚到提交无查询且新会话重新生效`() {
        val inputView = realChatInputView()
        val service = services.last()
        service.hostKeyEventSender = { true }

        inputView.onExpressionWindowHidden()
        assertTrue(service.sendNumericKeyEventAndReport(KeyEvent.KEYCODE_7))
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
        inputView.onSettingsMenuClick(SkbMenuMode.AiDoutu)
        assertEquals("请先输入文字，再点击搜索按钮", ShadowToast.getTextOfLatestToast())
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
        assertEquals("请先输入文字，再点击搜索按钮", ShadowToast.getTextOfLatestToast())
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

    private fun InputView.expressionState(): ExpressionPanelState =
        InputView::class.java.getDeclaredField("expressionPanelState").let { field ->
            field.isAccessible = true
            field.get(this) as ExpressionPanelState
        }

    private fun chatEditorInfo() = EditorInfo().apply {
        packageName = "com.tencent.mm"
        fieldId = 9
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        imeOptions = EditorInfo.IME_ACTION_SEND
    }
}
