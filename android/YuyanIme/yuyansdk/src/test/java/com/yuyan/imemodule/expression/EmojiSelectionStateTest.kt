package com.yuyan.imemodule.expression

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmojiSelectionStateTest {
    @Test
    fun `第一次和第二次选择按顺序生成组合键`() {
        val state = EmojiSelectionState()

        assertNull(state.select("angry"))
        assertEquals(EmojiSelectionStep.SECOND, state.step)
        assertEquals("angry__cry", state.select("cry"))
        assertEquals("angry", state.firstId)
        assertEquals("cry", state.secondId)
        assertEquals(EmojiSelectionStep.PREVIEW, state.step)
    }

    @Test
    fun `允许选择两个相同表情`() {
        val state = EmojiSelectionState()

        state.select("happy")

        assertEquals("happy__happy", state.select("happy"))
    }

    @Test
    fun `返回第一步时保留 first 并清除 second`() {
        val state = EmojiSelectionState()
        state.select("angry")
        state.select("cry")

        state.backToFirst()

        assertEquals(EmojiSelectionStep.FIRST, state.step)
        assertEquals("angry", state.firstId)
        assertNull(state.secondId)
        assertNull(state.combinationKey)
    }

    @Test
    fun `反向选择得到不同有序组合键`() {
        val forward = EmojiSelectionState().apply {
            select("angry")
            select("cry")
        }
        val reverse = EmojiSelectionState().apply {
            select("cry")
            select("angry")
        }

        assertNotEquals(forward.combinationKey, reverse.combinationKey)
        assertEquals("angry__cry", forward.combinationKey)
        assertEquals("cry__angry", reverse.combinationKey)
    }

    @Test
    fun `输入目标切换会清空完整选择状态`() {
        val state = EmojiSelectionState().apply {
            select("happy")
            select("laugh")
        }

        state.reset()

        assertEquals(EmojiSelectionStep.FIRST, state.step)
        assertNull(state.firstId)
        assertNull(state.secondId)
        assertNull(state.combinationKey)
    }
}
