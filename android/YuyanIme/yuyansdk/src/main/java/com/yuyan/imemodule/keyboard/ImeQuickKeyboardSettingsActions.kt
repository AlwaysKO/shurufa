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
