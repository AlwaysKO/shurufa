package com.yuyan.imemodule.expression

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostTextCommitDispatcherTest {
    @Test
    fun `宿主提交成功才通知统一文字追踪`() {
        val events = mutableListOf<String>()

        val committed = HostTextCommitDispatcher.dispatch(
            text = "语音整段",
            kind = ExpressionCommitKind.COMPLETE,
            commitToHost = { events += "commit"; true },
            notifyCommitted = { text, kind -> events += "notify:$text:$kind" },
        )

        assertTrue(committed)
        assertEquals(listOf("commit", "notify:语音整段:COMPLETE"), events)
    }

    @Test
    fun `宿主拒绝提交时不记录查询`() {
        val events = mutableListOf<String>()

        val committed = HostTextCommitDispatcher.dispatch(
            text = "failed",
            kind = ExpressionCommitKind.INCREMENTAL,
            commitToHost = { events += "commit"; false },
            notifyCommitted = { text, kind -> events += "notify:$text:$kind" },
        )

        assertFalse(committed)
        assertEquals(listOf("commit"), events)
    }
}
