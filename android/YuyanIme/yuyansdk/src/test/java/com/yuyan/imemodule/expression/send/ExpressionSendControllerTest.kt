package com.yuyan.imemodule.expression.send

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpressionSendControllerTest {
    private val expression = PreparedExpression(
        file = File("/tmp/expression.webp"),
        mimeType = "image/webp",
        displayName = "expression.webp",
    )

    @Test
    fun `取消从不发送而连续确认只发送一次`() = runBlocking {
        val sender = RecordingSender(ExpressionSendResult.Sent)
        val controller = ExpressionSendController(sender)

        controller.prepare(expression)
        controller.cancel()
        assertEquals(0, sender.calls)

        controller.prepare(expression)
        assertSame(ExpressionSendResult.Sent, controller.confirm())
        assertSame(ExpressionSendResult.NotPrepared, controller.confirm())
        assertEquals(1, sender.calls)
    }

    @Test
    fun `发送失败保留待发送内容和弹层状态`() = runBlocking {
        val failure = ExpressionSendResult.Failed("编辑器拒绝图片")
        val controller = ExpressionSendController(RecordingSender(failure))
        controller.prepare(expression)

        assertSame(failure, controller.confirm())

        assertTrue(controller.state is ExpressionSendState.Failed)
        assertEquals(expression, controller.prepared)
        assertFalse(controller.shouldClose)
    }

    @Test
    fun `发送成功清空内容并关闭`() = runBlocking {
        val controller = ExpressionSendController(RecordingSender(ExpressionSendResult.Sent))
        controller.prepare(expression)

        controller.confirm()

        assertNull(controller.prepared)
        assertTrue(controller.shouldClose)
        assertEquals(ExpressionSendState.Idle, controller.state)
    }

    @Test
    fun `目标不支持图片时返回明确降级结果`() = runBlocking {
        val controller = ExpressionSendController(
            RecordingSender(ExpressionSendResult.UnsupportedTarget),
        )
        controller.prepare(expression)

        assertSame(ExpressionSendResult.UnsupportedTarget, controller.confirm())
        assertTrue(controller.state is ExpressionSendState.Failed)
        assertEquals(expression, controller.prepared)
    }

    private class RecordingSender(
        private val result: ExpressionSendResult,
    ) : ExpressionSender {
        var calls = 0

        override suspend fun send(expression: PreparedExpression): ExpressionSendResult {
            calls += 1
            return result
        }
    }
}
