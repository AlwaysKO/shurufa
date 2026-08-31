package com.yuyan.imemodule.keyboard

import com.yuyan.imemodule.prefs.behavior.SkbMenuMode

/**
 * 工具栏的一个视觉槽。空槽没有菜单动作，因此不能被持久化或触发点击能力。
 */
data class KeyboardToolbarSlot private constructor(val skbMenuMode: SkbMenuMode?) {
    companion object {
        fun action(mode: SkbMenuMode): KeyboardToolbarSlot = KeyboardToolbarSlot(mode)

        val Placeholder = KeyboardToolbarSlot(null)
    }
}

/**
 * 候选栏左右固定按钮之间的工具模型。
 *
 * 这里只处理持久化菜单名称，不读取 Context、资源或数据库，方便视图层在拿到数据库项目后
 * 稳定地合并固定入口。下标从截图中的第二个按钮开始计算。
 */
object KeyboardToolbarModel {
    const val PLACEHOLDER_VIEW_TYPE = 1

    private val fixedModes = listOf(
        SkbMenuMode.Emojicon,
        SkbMenuMode.QuickKeyboard,
        SkbMenuMode.ClipBoard,
        SkbMenuMode.TextEdit,
        SkbMenuMode.AiDoutu,
    )

    /**
     * 固定截图中的五个中间按钮，并保留所有非固定数据库动作（包括重复项）。
     * 传入列表只读取，不会被原地排序或删除。
     */
    fun merge(existing: List<SkbMenuMode>): List<KeyboardToolbarSlot> {
        val remaining = existing.filterNot { it in fixedModes }
        return buildList(fixedModes.size + remaining.size) {
            addAll(fixedModes.map(KeyboardToolbarSlot::action))
            addAll(remaining.map(KeyboardToolbarSlot::action))
        }
    }
}

object KeyboardToolbarMetrics {
    const val TOTAL_VISIBLE_SLOT_COUNT = 7
    const val FUNCTION_ICON_BASE = 78
    const val COLLAPSE_ICON_WIDTH_BASE = 26
    const val COLLAPSE_ICON_HEIGHT_BASE = 18
    const val HEIGHT_TO_WIDTH_RATIO = 0.09f

    fun slotWidth(keyboardWidth: Int): Int = keyboardWidth / TOTAL_VISIBLE_SLOT_COUNT

    fun functionIconSize(keyboardWidth: Int, rowHeight: Int): Int = minOf(
        keyboardWidth * FUNCTION_ICON_BASE / 1080,
        (rowHeight * 0.72f).toInt(),
    ).coerceAtLeast(1)
}
