package com.yuyan.imemodule.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class ExpressionCommitDispatcherTest {
    @Test
    fun `候选确认时先上屏再触发表情查询`() {
        val events = mutableListOf<String>()

        ExpressionCommitDispatcher.dispatch(
            text = "你好",
            commitText = { events += "commit:$it" },
            notifyExpression = { events += "expression:$it" },
        )

        assertEquals(listOf("commit:你好", "expression:你好"), events)
    }

    @Test
    fun `空候选只执行上屏不触发表情查询`() {
        val events = mutableListOf<String>()

        ExpressionCommitDispatcher.dispatch(
            text = "",
            commitText = { events += "commit:$it" },
            notifyExpression = { events += "expression:$it" },
        )

        assertEquals(listOf("commit:"), events)
    }
}
