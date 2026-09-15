package com.yuyan.imemodule.data

import com.yuyan.imemodule.R
import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.entity.SkbFunItem
import com.yuyan.imemodule.keyboard.KeyboardToolbarModel
import com.yuyan.imemodule.prefs.behavior.SkbMenuMode
import java.util.ArrayDeque

val menuSkbFunsPreset: Map<SkbMenuMode, SkbFunItem> = hashMapOf(
    SkbMenuMode.Emojicon to SkbFunItem(Launcher.instance.context.getString(R.string.emoji_setting), R.drawable.ic_menu_emoji, SkbMenuMode.Emojicon),
    SkbMenuMode.SwitchKeyboard to SkbFunItem(Launcher.instance.context.getString(R.string.changeKeyboard), R.drawable.ic_menu_keyboard, SkbMenuMode.SwitchKeyboard),
    SkbMenuMode.KeyboardHeight to SkbFunItem(Launcher.instance.context.getString(R.string.setting_ime_keyboard_height), R.drawable.ic_menu_height, SkbMenuMode.KeyboardHeight),
    SkbMenuMode.ClipBoard to SkbFunItem(Launcher.instance.context.getString(R.string.clipboard), R.drawable.ic_menu_clipboard, SkbMenuMode.ClipBoard),
    SkbMenuMode.Phrases to SkbFunItem(Launcher.instance.context.getString(R.string.phrases), R.drawable.ic_menu_phrases, SkbMenuMode.Phrases),
    SkbMenuMode.DarkTheme to SkbFunItem(Launcher.instance.context.getString(R.string.keyboard_theme_night), R.drawable.ic_menu_dark, SkbMenuMode.DarkTheme),
    SkbMenuMode.Feedback to SkbFunItem(Launcher.instance.context.getString(R.string.keyboard_feedback), R.drawable.ic_menu_touch, SkbMenuMode.Feedback),
    SkbMenuMode.OneHanded to SkbFunItem(Launcher.instance.context.getString(R.string.keyboard_one_handed_mod), R.drawable.ic_menu_one_hand, SkbMenuMode.OneHanded),
    SkbMenuMode.NumberRow to SkbFunItem(Launcher.instance.context.getString(R.string.engish_full_keyboard), R.drawable.ic_menu_shuzihang, SkbMenuMode.NumberRow),
    SkbMenuMode.JianFan to SkbFunItem(Launcher.instance.context.getString(R.string.setting_jian_fan), R.drawable.ic_menu_fanti, SkbMenuMode.JianFan),
    SkbMenuMode.Mnemonic to SkbFunItem(Launcher.instance.context.getString(R.string.keyboard_mnemonic_show), R.drawable.ic_menu_mnemonic, SkbMenuMode.Mnemonic),
    SkbMenuMode.FloatKeyboard to SkbFunItem(Launcher.instance.context.getString(R.string.keyboard_menu_float), R.drawable.ic_menu_float, SkbMenuMode.FloatKeyboard),
    SkbMenuMode.FlowerTypeface to SkbFunItem(Launcher.instance.context.getString(R.string.keyboard_flower_typeface), R.drawable.ic_menu_flower, SkbMenuMode.FlowerTypeface),
    SkbMenuMode.Custom to SkbFunItem(Launcher.instance.context.getString(R.string.skb_item_custom), R.drawable.ic_menu_custom, SkbMenuMode.Custom),
    SkbMenuMode.Settings to SkbFunItem(Launcher.instance.context.getString(R.string.skb_item_settings), R.drawable.ic_menu_setting, SkbMenuMode.Settings),
    SkbMenuMode.CloseSKB to SkbFunItem(Launcher.instance.context.getString(R.string.keyboard_iv_menu_close), R.drawable.ic_menu_arrow_down, SkbMenuMode.CloseSKB),
    SkbMenuMode.ClearClipBoard to SkbFunItem(Launcher.instance.context.getString(R.string.clipboard_clear), R.drawable.ic_menu_delete, SkbMenuMode.ClearClipBoard),
    SkbMenuMode.Emoticon to SkbFunItem(Launcher.instance.context.getString(R.string.emoticons), R.drawable.ic_menu_emoji_emoticons, SkbMenuMode.Emoticon),
    SkbMenuMode.AddPhrases to SkbFunItem(Launcher.instance.context.getString(R.string.add_phrases), R.drawable.ic_menu_plus, SkbMenuMode.AddPhrases),
    SkbMenuMode.LockClipBoard to SkbFunItem(Launcher.instance.context.getString(R.string.lock_view), R.drawable.icon_symbol_lock, SkbMenuMode.LockClipBoard),
    SkbMenuMode.TextEdit to SkbFunItem(Launcher.instance.context.getString(R.string.menu_text_edit), R.drawable.ic_menu_cursor_icon, SkbMenuMode.TextEdit),
    SkbMenuMode.Voice to SkbFunItem(Launcher.instance.context.getString(R.string.voice_input), R.drawable.ic_menu_voice, SkbMenuMode.Voice),
    SkbMenuMode.Handwriting to SkbFunItem(Launcher.instance.context.getString(R.string.ime_settings_handwriting), R.drawable.ic_menu_handwriting, SkbMenuMode.Handwriting),
    SkbMenuMode.PinyinHandWriting to SkbFunItem(Launcher.instance.context.getString(R.string.keyboard_name_hand), R.drawable.ic_menu_handwriting, SkbMenuMode.PinyinHandWriting),
    SkbMenuMode.QuickKeyboard to SkbFunItem(Launcher.instance.context.getString(R.string.quick_keyboard_theme), R.drawable.ic_menu_quick_keyboard, SkbMenuMode.QuickKeyboard),
    SkbMenuMode.AiDoutu to SkbFunItem(Launcher.instance.context.getString(R.string.ai_sticker_search), R.drawable.ic_menu_ai_sticker_search, SkbMenuMode.AiDoutu),
)

/**
 * 将数据库工具项映射到纯 Kotlin 工具栏规格，同时保留非固定项的原对象和重复语义。
 */
fun mergeKeyboardToolbarItems(existing: List<SkbFunItem>): List<SkbFunItem?> {
    val itemsByMode = mutableMapOf<SkbMenuMode, ArrayDeque<SkbFunItem>>()
    existing.forEach { item ->
        itemsByMode.getOrPut(item.skbMenuMode, ::ArrayDeque).addLast(item)
    }
    return KeyboardToolbarModel.merge(existing.map(SkbFunItem::skbMenuMode)).map { slot ->
        slot.skbMenuMode?.let { mode ->
            itemsByMode[mode]?.pollFirst() ?: requireNotNull(menuSkbFunsPreset[mode]) {
                "Missing toolbar preset for ${mode.name}"
            }
        }
    }
}

/** RecyclerView 直接消费的视觉槽；slotId 在 Diff 期间唯一且稳定。 */
data class KeyboardToolbarVisualItem(
    val slotId: String,
    val item: SkbFunItem?,
)

/**
 * 在不改变纯 [KeyboardToolbarModel] 与既有持久化模型的前提下，为每个视觉槽补稳定身份。
 * 数据库动作以持久化 mode 名称为主身份，并用 occurrence 保留重复动作语义。
 */
fun mergeKeyboardToolbarVisualItems(existing: List<SkbFunItem>): List<KeyboardToolbarVisualItem> {
    val itemsByMode = mutableMapOf<SkbMenuMode, ArrayDeque<SkbFunItem>>()
    existing.forEach { item ->
        itemsByMode.getOrPut(item.skbMenuMode, ::ArrayDeque).addLast(item)
    }
    val occurrences = mutableMapOf<SkbMenuMode, Int>()
    return KeyboardToolbarModel.merge(existing.map(SkbFunItem::skbMenuMode)).mapIndexed { index, slot ->
        val mode = slot.skbMenuMode
        if (mode == null) {
            KeyboardToolbarVisualItem("placeholder:$index", null)
        } else {
            val item = itemsByMode[mode]?.pollFirst() ?: requireNotNull(menuSkbFunsPreset[mode]) {
                "Missing toolbar preset for ${mode.name}"
            }
            val fixedId = when (mode) {
                SkbMenuMode.Emojicon -> "fixed:emojicon"
                SkbMenuMode.QuickKeyboard -> "fixed:quick_keyboard"
                SkbMenuMode.ClipBoard -> "fixed:clipboard"
                SkbMenuMode.TextEdit -> "fixed:text_edit"
                SkbMenuMode.AiDoutu -> "fixed:ai_doutu"
                else -> null
            }
            val occurrence = occurrences[mode] ?: 0
            occurrences[mode] = occurrence + 1
            KeyboardToolbarVisualItem(fixedId ?: "database:${mode.name}:$occurrence", item)
        }
    }
}

/** 临时工具列表也使用显式槽身份，避免 nullable item 参与 Diff 身份判断。 */
fun keyboardToolbarVisualItems(
    namespace: String,
    items: List<SkbFunItem>,
): List<KeyboardToolbarVisualItem> {
    val occurrences = mutableMapOf<SkbMenuMode, Int>()
    return items.map { item ->
        val occurrence = occurrences[item.skbMenuMode] ?: 0
        occurrences[item.skbMenuMode] = occurrence + 1
        KeyboardToolbarVisualItem("$namespace:${item.skbMenuMode.name}:$occurrence", item)
    }
}
