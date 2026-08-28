package com.yuyan.imemodule.expression

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpressionComposingTextSourceTest {
    @Test
    fun `英文schema使用native composing和raw preedit取当前候选`() {
        var isComposing = true
        var rawInput = "hello"
        val source = ExpressionComposingTextSource(
            isComposing = { isComposing },
            rawInput = { rawInput },
            isAssociate = { false },
            candidateText = { "Hello" },
        )

        assertEquals("Hello", source.currentText(activeCandidateIndex = 0))

        isComposing = false
        assertNull(source.currentText(activeCandidateIndex = 0))

        isComposing = true
        rawInput = ""
        assertNull(source.currentText(activeCandidateIndex = 0))
    }

    @Test
    fun `中文native composing非空时取当前候选且激活不存在回退首候选`() {
        val candidates = mapOf(0 to "民营企业")
        val source = ExpressionComposingTextSource(
            isComposing = { true },
            rawInput = { "min'ying'qi'ye" },
            isAssociate = { false },
            candidateText = candidates::get,
        )

        assertEquals("民营企业", source.currentText(activeCandidateIndex = -1))
    }

    @Test
    fun `联想或schema切换后的残留候选不能冒充当前组合`() {
        var isComposing = false
        var rawInput = "old"
        var associate = false
        val source = ExpressionComposingTextSource(
            isComposing = { isComposing },
            rawInput = { rawInput },
            isAssociate = { associate },
            candidateText = { "旧schema候选" },
        )

        assertNull(source.currentText(activeCandidateIndex = 0))

        isComposing = true
        associate = true
        assertNull(source.currentText(activeCandidateIndex = 0))
    }

    @Test
    fun `clear同步清理native组合与raw input边界`() {
        var isComposing = true
        var rawInput = "active"
        var cleared = false
        val source = ExpressionComposingTextSource(
            isComposing = { isComposing },
            rawInput = { rawInput },
            isAssociate = { false },
            candidateText = { "正在输入" },
            clearComposition = {
                cleared = true
                isComposing = false
                rawInput = ""
            },
        )

        assertEquals("正在输入", source.currentText(0))
        source.clear()

        assertTrue(cleared)
        assertFalse(isComposing)
        assertNull(source.currentText(0))
    }
}
