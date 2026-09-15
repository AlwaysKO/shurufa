package com.yuyan.imemodule.prefs.behavior

import com.yuyan.imemodule.view.preference.ManagedPreference

enum class SkbMenuMode {
    SwitchKeyboard,
    KeyboardHeight,
    DarkTheme,
    Feedback,
    NumberRow,
    JianFan,
    LockEnglish,
    SymbolShow,
    CandidatesMore,
    Mnemonic,
    FlowerTypeface,
    EmojiInput,
    Handwriting,
    Custom,
    SettingsMenu,
    Settings,
    FloatKeyboard,
    OneHanded,
    PinyinT9,
    Pinyin26Jian,
    PinyinLx17,
    PinyinHandWriting,
    Pinyin26Double,
    PinyinStroke,
    ClipBoard,
    ClearClipBoard,
    Phrases,
    AddPhrases,
    CloseSKB,
    Emojicon,
    Emoticon,
    LockClipBoard,
    TextEdit,
    Voice,
    // 菜单动作按 name 持久化；新值只追加，兼容可能仍读取 ordinal 的外部调用方。
    QuickKeyboard,
    AiDoutu;

    companion object : ManagedPreference.StringLikeCodec<SkbMenuMode> {
        override fun decode(raw: String): SkbMenuMode =
            SkbMenuMode.valueOf(raw)
    }
}
