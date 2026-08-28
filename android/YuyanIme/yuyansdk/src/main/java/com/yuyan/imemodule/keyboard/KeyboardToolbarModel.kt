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
    private val pinnedModes = setOf(
        SkbMenuMode.Emojicon,
        SkbMenuMode.QuickKeyboard,
        SkbMenuMode.AiDoutu,
    )

    private val preferredModes = listOf(
        SkbMenuMode.Voice,
        SkbMenuMode.PinyinHandWriting,
        SkbMenuMode.Handwriting,
    )

    /**
     * 固定表情、快捷键盘和 AI 斗图，并保留所有非固定数据库动作（包括重复项）。
     * 传入列表只读取，不会被原地排序或删除。
     */
    fun merge(existing: List<SkbMenuMode>): List<KeyboardToolbarSlot> {
        val remaining = existing.filterNotTo(mutableListOf()) { it in pinnedModes }
        val featured = ArrayList<SkbMenuMode>(FEATURED_SLOT_COUNT)

        preferredModes.forEach { preferred ->
            if (featured.size == FEATURED_SLOT_COUNT) return@forEach
            val index = remaining.indexOf(preferred)
            if (index >= 0) featured += remaining.removeAt(index)
        }

        while (featured.size < FEATURED_SLOT_COUNT && remaining.isNotEmpty()) {
            featured += remaining.removeAt(0)
        }

        return buildList(existing.size + PINNED_SLOT_COUNT) {
            add(KeyboardToolbarSlot.action(SkbMenuMode.Emojicon))
            add(KeyboardToolbarSlot.action(SkbMenuMode.QuickKeyboard))
            addAll(featured.map(KeyboardToolbarSlot::action))
            repeat(FEATURED_SLOT_COUNT - featured.size) {
                add(KeyboardToolbarSlot.Placeholder)
            }
            add(KeyboardToolbarSlot.action(SkbMenuMode.AiDoutu))
            addAll(remaining.map(KeyboardToolbarSlot::action))
        }
    }

    private const val FEATURED_SLOT_COUNT = 2
    private const val PINNED_SLOT_COUNT = 3
}
