package com.yuyan.imemodule.expression

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpressionPreviewJobSlotTest {
    @Test
    fun `旧IO响应晚到不能取消并替换新请求预览`() = runBlocking {
        val slot = ExpressionPreviewJobSlot()
        val rendered = mutableListOf<String>()

        slot.beginRequest(1)
        // 模拟旧响应已经通过 accept，随后主线程开始了新请求及新预览。
        slot.beginRequest(2)
        val fresh = previewJob { rendered += "fresh" }
        assertTrue(slot.installIfCurrent(2, fresh))

        val stale = previewJob { rendered += "stale" }
        assertFalse(slot.installIfCurrent(1, stale))
        yield()

        assertTrue(stale.isCancelled)
        assertFalse(fresh.isCancelled)
        assertEquals(listOf("fresh"), rendered)
    }

    private fun kotlinx.coroutines.CoroutineScope.previewJob(block: () -> Unit): Job =
        launch(start = CoroutineStart.LAZY) { block() }
}
