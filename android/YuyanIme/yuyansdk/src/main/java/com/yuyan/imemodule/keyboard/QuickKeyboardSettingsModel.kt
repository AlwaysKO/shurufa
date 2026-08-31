package com.yuyan.imemodule.keyboard

import com.yuyan.imemodule.application.CustomConstant
import com.yuyan.imemodule.manager.InputModeSwitcher

enum class QuickKeyboardLayoutId {
    CHINESE_T9,
    CHINESE_QWERTY,
    ENGLISH_QWERTY,
    HANDWRITING,
    STROKE,
    NUMBER,
    CHINESE_SYMBOL,
    ENGLISH_SYMBOL,
    TEXT_EDIT,
    LX17,
}

enum class SymbolPage { CHINESE, ENGLISH }

sealed interface QuickKeyboardAction {
    data class ChineseMode(val layout: Int, val schema: String) : QuickKeyboardAction
    data object EnglishQwerty : QuickKeyboardAction
    data class UserKey(val keyCode: Int) : QuickKeyboardAction
    data class Symbol(val page: SymbolPage) : QuickKeyboardAction
}

data class QuickKeyboardLayoutOption(
    val id: QuickKeyboardLayoutId,
    val action: QuickKeyboardAction,
)

data class QuickThemeOption(
    val themeId: String,
    val isDark: Boolean,
)

/**
 * 输入法内快捷设置的稳定能力清单。模型不依赖 Context 或 View，视图只负责把标识翻译成文案。
 */
object QuickKeyboardSettingsModel {
    val layouts = listOf(
        QuickKeyboardLayoutOption(
            QuickKeyboardLayoutId.CHINESE_T9,
            QuickKeyboardAction.ChineseMode(
                InputModeSwitcher.MASK_SKB_LAYOUT_T9_PINYIN,
                CustomConstant.SCHEMA_ZH_T9,
            ),
        ),
        QuickKeyboardLayoutOption(
            QuickKeyboardLayoutId.CHINESE_QWERTY,
            QuickKeyboardAction.ChineseMode(
                InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_PINYIN,
                CustomConstant.SCHEMA_ZH_QWERTY,
            ),
        ),
        QuickKeyboardLayoutOption(QuickKeyboardLayoutId.ENGLISH_QWERTY, QuickKeyboardAction.EnglishQwerty),
        QuickKeyboardLayoutOption(
            QuickKeyboardLayoutId.HANDWRITING,
            QuickKeyboardAction.ChineseMode(
                InputModeSwitcher.MASK_SKB_LAYOUT_HANDWRITING,
                CustomConstant.SCHEMA_ZH_HANDWRITING,
            ),
        ),
        QuickKeyboardLayoutOption(
            QuickKeyboardLayoutId.STROKE,
            QuickKeyboardAction.ChineseMode(
                InputModeSwitcher.MASK_SKB_LAYOUT_STROKE,
                CustomConstant.SCHEMA_ZH_STROKE,
            ),
        ),
        QuickKeyboardLayoutOption(
            QuickKeyboardLayoutId.NUMBER,
            QuickKeyboardAction.UserKey(InputModeSwitcher.USER_KEYCODE_NUMBER),
        ),
        QuickKeyboardLayoutOption(
            QuickKeyboardLayoutId.CHINESE_SYMBOL,
            QuickKeyboardAction.Symbol(SymbolPage.CHINESE),
        ),
        QuickKeyboardLayoutOption(
            QuickKeyboardLayoutId.ENGLISH_SYMBOL,
            QuickKeyboardAction.Symbol(SymbolPage.ENGLISH),
        ),
        QuickKeyboardLayoutOption(
            QuickKeyboardLayoutId.TEXT_EDIT,
            QuickKeyboardAction.UserKey(InputModeSwitcher.USER_KEYCODE_TEXTEDIT),
        ),
        QuickKeyboardLayoutOption(
            QuickKeyboardLayoutId.LX17,
            QuickKeyboardAction.ChineseMode(
                InputModeSwitcher.MASK_SKB_LAYOUT_LX17,
                CustomConstant.SCHEMA_ZH_DOUBLE_LX17,
            ),
        ),
    )

    private val quickThemes = listOf(
        QuickThemeOption("SogouDefault", isDark = false),
        QuickThemeOption("SogouBlue", isDark = false),
        QuickThemeOption("WechatLayout", isDark = false),
        QuickThemeOption("SogouHuawei", isDark = false),
    )

    fun availableThemes(existingThemeIds: Set<String>): List<QuickThemeOption> =
        quickThemes.filter { it.themeId in existingThemeIds }

    fun isThemeSelectable(themeId: String, themes: List<QuickThemeOption>): Boolean =
        themes.any { it.themeId == themeId }

    fun selectedThemeId(activeThemeId: String, themes: List<QuickThemeOption>): String? =
        themes.firstOrNull { it.themeId == activeThemeId }?.themeId

    fun selectedLayout(
        layout: Int,
        isEnglish: Boolean,
        symbolPage: SymbolPage? = null,
    ): QuickKeyboardLayoutId? {
        if (symbolPage != null) {
            return if (symbolPage == SymbolPage.CHINESE) {
                QuickKeyboardLayoutId.CHINESE_SYMBOL
            } else {
                QuickKeyboardLayoutId.ENGLISH_SYMBOL
            }
        }
        return selectedInputLayout(layout, isEnglish)
    }

    private fun selectedInputLayout(layout: Int, isEnglish: Boolean): QuickKeyboardLayoutId? = when (layout) {
        InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_ABC -> QuickKeyboardLayoutId.ENGLISH_QWERTY
        InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_PINYIN -> if (isEnglish) {
            QuickKeyboardLayoutId.ENGLISH_QWERTY
        } else {
            QuickKeyboardLayoutId.CHINESE_QWERTY
        }
        InputModeSwitcher.MASK_SKB_LAYOUT_T9_PINYIN -> QuickKeyboardLayoutId.CHINESE_T9
        InputModeSwitcher.MASK_SKB_LAYOUT_HANDWRITING -> QuickKeyboardLayoutId.HANDWRITING
        InputModeSwitcher.MASK_SKB_LAYOUT_STROKE -> QuickKeyboardLayoutId.STROKE
        InputModeSwitcher.MASK_SKB_LAYOUT_NUMBER -> QuickKeyboardLayoutId.NUMBER
        InputModeSwitcher.MASK_SKB_LAYOUT_TEXTEDIT -> QuickKeyboardLayoutId.TEXT_EDIT
        InputModeSwitcher.MASK_SKB_LAYOUT_LX17 -> QuickKeyboardLayoutId.LX17
        else -> null
    }
}

interface QuickKeyboardSettingsActions {
    val availableThemeIds: Set<String>
    val currentThemeId: String

    fun applyLayout(action: QuickKeyboardAction)
    fun applyTheme(themeId: String): Boolean
    fun closeQuickSettings()
}

/**
 * 共享点击、返回和再次点击语义，使 SettingsContainer 只负责渲染。
 */
class QuickKeyboardSettingsController(
    private val actions: QuickKeyboardSettingsActions,
) {
    var isVisible: Boolean = false
        private set

    val selectedThemeId: String
        get() = actions.currentThemeId

    val themes: List<QuickThemeOption>
        get() = QuickKeyboardSettingsModel.availableThemes(actions.availableThemeIds)

    fun show() {
        isVisible = true
    }

    /** 供同一容器切换到普通设置内容，不执行“返回键盘”动作。 */
    fun dismiss() {
        isVisible = false
    }

    /** 返回 true 表示切换后面板处于显示状态。 */
    fun toggle(): Boolean {
        if (isVisible) {
            close()
        } else {
            show()
        }
        return isVisible
    }

    fun selectLayout(id: QuickKeyboardLayoutId): Boolean {
        if (!isVisible) return false
        val option = QuickKeyboardSettingsModel.layouts.firstOrNull { it.id == id } ?: return false
        isVisible = false
        // 先恢复输入视图，再应用目标动作；符号等非输入容器不会被第二次“返回”覆盖。
        actions.closeQuickSettings()
        actions.applyLayout(option.action)
        return true
    }

    fun selectTheme(themeId: String): Boolean {
        if (!isVisible || !QuickKeyboardSettingsModel.isThemeSelectable(themeId, themes)) return false
        return actions.applyTheme(themeId)
    }

    fun handleBack(): Boolean {
        if (!isVisible) return false
        close()
        return true
    }

    private fun close() {
        isVisible = false
        actions.closeQuickSettings()
    }
}
