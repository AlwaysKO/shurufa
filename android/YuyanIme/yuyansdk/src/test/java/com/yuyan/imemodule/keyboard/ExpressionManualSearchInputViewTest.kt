package com.yuyan.imemodule.keyboard

import android.content.Context
import android.view.inputmethod.EditorInfo
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.data.emojicon.YuyanEmojiCompat
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.expression.ExpressionPanelPresentation
import com.yuyan.imemodule.expression.ExpressionPanelState
import com.yuyan.imemodule.expression.ExpressionComposingTextSource
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
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
class ExpressionManualSearchInputViewTest {
    private lateinit var context: Context
    private val services = mutableListOf<ImeService>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Launcher::class.java.getDeclaredField("context").apply {
            isAccessible = true
            set(Launcher.instance, context)
        }
        AppPrefs.init(PreferenceManager.getDefaultSharedPreferences(context))
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
        services.forEach(ImeService::onDestroy)
        services.clear()
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
            compositionText = { "min'ying'qi'ye" },
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
        inputView.expressionComposingTextSource = ExpressionComposingTextSource(
            compositionText = { null },
            isAssociate = { false },
            candidateText = { null },
        )
        inputView.notifyExpressionTextCommitted("旧输入框文字")
        inputView.onSettingsMenuClick(SkbMenuMode.AiDoutu)
        assertEquals("旧输入框文字", inputView.expressionState().query)

        inputView.onExpressionInputTargetChanged(EditorInfo())
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
            compositionText = { "" },
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
            compositionText = { null },
            isAssociate = { false },
            candidateText = { null },
        )
        inputView.isAddPhrases = true

        inputView.commitCandidateAndNotify("短语内部文字")
        inputView.onSettingsMenuClick(SkbMenuMode.AiDoutu)

        assertEquals("请先输入文字，再点击搜索按钮", ShadowToast.getTextOfLatestToast())
        assertNull(inputView.expressionState().query)
    }

    private fun realChatInputView(): InputView {
        val service = Robolectric.buildService(ImeService::class.java).create().get()
        services += service
        val inputView = service.onCreateInputView() as InputView
        inputView.expressionState().setChatEditor(true)
        return inputView
    }

    private fun InputView.expressionState(): ExpressionPanelState =
        InputView::class.java.getDeclaredField("expressionPanelState").let { field ->
            field.isAccessible = true
            field.get(this) as ExpressionPanelState
        }
}
