package com.yuyan.imemodule.keyboard

/**
 * 快捷设置动作需要的 IME 运行时能力。生产实现由 SettingsContainer 连接现有单例，测试可在
 * 不启动完整输入法服务的情况下验证同一套动作分派。
 */
interface ImeQuickKeyboardSettingsRuntime {
    val availableThemeIds: Set<String>
    val currentThemeId: String

    fun switchChinese(layout: Int, schema: String)
    fun switchEnglish()
    fun switchUserKey(keyCode: Int)
    fun showSymbol(page: SymbolPage)
    fun applyTheme(themeId: String): Boolean
    fun closeQuickSettings()
}

class ImeQuickKeyboardSettingsActions(
    private val runtime: ImeQuickKeyboardSettingsRuntime,
) : QuickKeyboardSettingsActions {
    override val availableThemeIds: Set<String>
        get() = runtime.availableThemeIds
    override val currentThemeId: String
        get() = runtime.currentThemeId

    override fun applyLayout(action: QuickKeyboardAction) {
        when (action) {
            is QuickKeyboardAction.ChineseMode -> runtime.switchChinese(action.layout, action.schema)
            QuickKeyboardAction.EnglishQwerty -> runtime.switchEnglish()
            is QuickKeyboardAction.UserKey -> runtime.switchUserKey(action.keyCode)
            is QuickKeyboardAction.Symbol -> runtime.showSymbol(action.page)
        }
    }

    override fun applyTheme(themeId: String): Boolean = runtime.applyTheme(themeId)

    override fun closeQuickSettings() = runtime.closeQuickSettings()
}

fun interface QuickSymbolSurface {
    fun show(page: SymbolPage)
}

/**
 * SettingsContainer 的生产运行时。模式与主题状态直接写入现有单例；仅把具体 View 展示及刷新
 * 作为末端回调交给宿主，因此同一类可以在 Robolectric 中连接真实符号容器验证。
 */
class AndroidImeQuickKeyboardSettingsRuntime(
    private val symbolSurface: QuickSymbolSurface,
    private val initializeSchema: (String) -> Unit = com.yuyan.inputmethod.core.Kernel::initImeSchema,
    private val onInputSurfaceSelected: () -> Unit = {},
    private val onThemeChanged: () -> Unit,
    private val onClose: (themeChanged: Boolean) -> Unit,
) : ImeQuickKeyboardSettingsRuntime {
    private var themeChanged = false

    override val availableThemeIds: Set<String>
        get() = com.yuyan.imemodule.data.theme.ThemeManager.getAllThemes()
            .mapTo(linkedSetOf()) { it.name }
    override val currentThemeId: String
        get() = com.yuyan.imemodule.data.theme.ThemeManager.activeTheme.name

    override fun switchChinese(layout: Int, schema: String) {
        onInputSurfaceSelected()
        com.yuyan.imemodule.manager.InputModeSwitcher.switchModeForSetting(
            layout to schema,
            initializeSchema,
        )
    }

    override fun switchEnglish() {
        onInputSurfaceSelected()
        com.yuyan.imemodule.manager.InputModeSwitcher.switchToEnglishForSetting(initializeSchema)
    }

    override fun switchUserKey(keyCode: Int) {
        onInputSurfaceSelected()
        com.yuyan.imemodule.manager.InputModeSwitcher.switchModeForUserKey(keyCode, initializeSchema)
    }

    override fun showSymbol(page: SymbolPage) = symbolSurface.show(page)

    override fun applyTheme(themeId: String): Boolean {
        if (!applyThemePreference(themeId)) return false
        themeChanged = true
        onThemeChanged()
        return true
    }

    override fun closeQuickSettings() {
        onClose(themeChanged)
        themeChanged = false
    }

    companion object {
        private val QUICK_THEME_IDS = setOf("MaterialLight", "MaterialDark")

        internal fun applyThemePreference(themeId: String): Boolean {
            if (themeId !in QUICK_THEME_IDS) return false
            val manager = com.yuyan.imemodule.data.theme.ThemeManager
            val theme = manager.getTheme(themeId) ?: return false
            manager.prefs.followSystemDayNightTheme.setValue(false)
            manager.setNormalModeTheme(theme)
            return true
        }
    }
}
