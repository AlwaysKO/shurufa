package com.yuyan.imemodule.expression

import org.junit.Assert.assertEquals
import org.junit.Test

class ExpressionSelectionInputClearerTest {
    @Test
    fun `选中斗图时先清空组合态再删除光标两侧全部输入`() {
        val events = mutableListOf<String>()
        var hasText = true

        ExpressionSelectionInputClearer.clear(
            clearComposition = { events += "composition" },
            selectAllText = { events += "select-all"; false },
            replaceSelectedText = { error("全选失败时不应替换") },
            textBeforeCursor = { limit -> events += "before:$limit"; if (hasText) "玻璃心" else "" },
            textAfterCursor = { limit -> events += "after:$limit"; if (hasText) "后缀" else "" },
            deleteSurroundingText = { before, after ->
                events += "delete:$before:$after"
                hasText = false
                true
            },
        )

        assertEquals(
            listOf(
                "composition",
                "select-all",
                "before:1000",
                "after:1000",
                "delete:3:2",
                "before:1000",
                "after:1000",
            ),
            events,
        )
    }

    @Test
    fun `空输入仍清空组合态但不发送无效删除`() {
        val events = mutableListOf<String>()

        ExpressionSelectionInputClearer.clear(
            clearComposition = { events += "composition" },
            selectAllText = { false },
            replaceSelectedText = { error("全选失败时不应替换") },
            textBeforeCursor = { "" },
            textAfterCursor = { "" },
            deleteSurroundingText = { _, _ -> events += "delete"; true },
        )

        assertEquals(listOf("composition"), events)
    }

    @Test
    fun `宿主支持全选替换时不再执行分块删除`() {
        var deleteCalls = 0

        val cleared = ExpressionSelectionInputClearer.clear(
            clearComposition = {},
            selectAllText = { true },
            replaceSelectedText = { true },
            textBeforeCursor = { "" },
            textAfterCursor = { "" },
            deleteSurroundingText = { _, _ -> deleteCalls += 1; true },
        )

        assertEquals(true, cleared)
        assertEquals(0, deleteCalls)
    }

    @Test
    fun `超过单次读取上限的长文本会分块删到完全为空`() {
        var beforeLength = 2_505
        var afterLength = 1_501
        var deleteCalls = 0

        val cleared = ExpressionSelectionInputClearer.clear(
            clearComposition = {},
            selectAllText = { false },
            replaceSelectedText = { error("全选失败时不应替换") },
            textBeforeCursor = { limit -> "a".repeat(minOf(limit, beforeLength)) },
            textAfterCursor = { limit -> "b".repeat(minOf(limit, afterLength)) },
            deleteSurroundingText = { before, after ->
                beforeLength -= before
                afterLength -= after
                deleteCalls += 1
                true
            },
        )

        assertEquals(true, cleared)
        assertEquals(0, beforeLength)
        assertEquals(0, afterLength)
        assertEquals(3, deleteCalls)
    }

    @Test
    fun `宿主拒绝全选和删除时返回未清空便于中止发送`() {
        val cleared = ExpressionSelectionInputClearer.clear(
            clearComposition = {},
            selectAllText = { false },
            replaceSelectedText = { error("全选失败时不应替换") },
            textBeforeCursor = { "仍有文字" },
            textAfterCursor = { "" },
            deleteSurroundingText = { _, _ -> false },
        )

        assertEquals(false, cleared)
    }

    @Test
    fun `宿主无法读取光标文本时不假定已清空`() {
        val cleared = ExpressionSelectionInputClearer.clear(
            clearComposition = {},
            selectAllText = { false },
            replaceSelectedText = { error("全选失败时不应替换") },
            textBeforeCursor = { null },
            textAfterCursor = { null },
            deleteSurroundingText = { _, _ -> true },
        )

        assertEquals(false, cleared)
    }

    @Test
    fun `全选成功但宿主拒绝置空时不把选区两侧空文本误判为已清空`() {
        var cursorReadCalls = 0

        val cleared = ExpressionSelectionInputClearer.clear(
            clearComposition = {},
            selectAllText = { true },
            replaceSelectedText = { false },
            textBeforeCursor = { cursorReadCalls += 1; "" },
            textAfterCursor = { cursorReadCalls += 1; "" },
            deleteSurroundingText = { _, _ -> true },
        )

        assertEquals(false, cleared)
        assertEquals(0, cursorReadCalls)
    }
}
