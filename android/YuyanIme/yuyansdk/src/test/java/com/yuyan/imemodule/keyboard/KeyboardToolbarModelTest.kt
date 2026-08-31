package com.yuyan.imemodule.keyboard

import com.yuyan.imemodule.prefs.behavior.SkbMenuMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Test

class KeyboardToolbarModelTest {
    private val referenceOrder = listOf(
        SkbMenuMode.Emojicon,
        SkbMenuMode.QuickKeyboard,
        SkbMenuMode.ClipBoard,
        SkbMenuMode.TextEdit,
        SkbMenuMode.AiDoutu,
    )

    @Test
    fun `空数据库仍固定提供截图中的五个中间按钮`() {
        val output = KeyboardToolbarModel.merge(emptyList())
        assertEquals(referenceOrder, output.mapNotNull { it.skbMenuMode })
        assertFalse(output.any { it.skbMenuMode == null })
    }

    @Test
    fun `固定按钮只出现一次且数据库其他动作追加在可滚动区域`() {
        val existing = listOf(
            SkbMenuMode.Voice,
            SkbMenuMode.ClipBoard,
            SkbMenuMode.Emojicon,
            SkbMenuMode.Phrases,
            SkbMenuMode.TextEdit,
            SkbMenuMode.AiDoutu,
        )
        val modes = KeyboardToolbarModel.merge(existing).mapNotNull { it.skbMenuMode }
        assertEquals(referenceOrder, modes.take(referenceOrder.size))
        assertEquals(listOf(SkbMenuMode.Voice, SkbMenuMode.Phrases), modes.drop(referenceOrder.size))
        referenceOrder.forEach { mode -> assertEquals(1, modes.count { it == mode }) }
    }

    @Test
    fun `非固定重复动作保持原顺序且不修改调用方集合`() {
        val existing = mutableListOf(SkbMenuMode.Voice, SkbMenuMode.Voice, SkbMenuMode.Phrases)
        val snapshot = existing.toList()
        val output = KeyboardToolbarModel.merge(existing)
        assertEquals(snapshot, existing)
        assertNotSame(existing, output)
        assertEquals(snapshot, output.drop(referenceOrder.size).mapNotNull { it.skbMenuMode })
    }

    @Test
    fun `工具栏使用1080参考图的图标画布和七个总槽位`() {
        assertEquals(7, KeyboardToolbarMetrics.TOTAL_VISIBLE_SLOT_COUNT)
        assertEquals(78, KeyboardToolbarMetrics.FUNCTION_ICON_BASE)
        assertEquals(26, KeyboardToolbarMetrics.COLLAPSE_ICON_WIDTH_BASE)
        assertEquals(18, KeyboardToolbarMetrics.COLLAPSE_ICON_HEIGHT_BASE)
        assertEquals(0.09f, KeyboardToolbarMetrics.HEIGHT_TO_WIDTH_RATIO, 0.0001f)
    }
}
