package com.yuyan.imemodule.expression

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpressionQueryCoordinatorTest {
    @Test
    fun `组合态候选不发布而最终上屏文字才发布`() = runBlocking {
        val seen = mutableListOf<String>()
        val coordinator = ExpressionQueryCoordinator(this, 150) { seen += it }

        coordinator.onComposingChanged("放")
        delay(170)
        assertTrue(seen.isEmpty())

        coordinator.onCommitted("放箭")
        delay(170)

        assertEquals(listOf("放箭"), seen)
        coordinator.close()
    }

    @Test
    fun `新组合态使已提交查询的过期响应失效`() = runBlocking {
        val coordinator = ExpressionQueryCoordinator(this, 30) { }

        coordinator.onCommitted("旧候选")
        delay(40)
        assertTrue(coordinator.acceptResponse(1))

        assertTrue(coordinator.onComposingChanged("新候选"))
        assertFalse(coordinator.acceptResponse(1))
        coordinator.close()
    }

    @Test
    fun `候选上屏后保留最后查询词`() = runBlocking {
        val seen = mutableListOf<String>()
        val coordinator = ExpressionQueryCoordinator(this, 30) { seen += it }

        coordinator.onCommitted("放箭")
        assertFalse(coordinator.onComposingChanged(null))
        withTimeout(1_000) {
            while (seen.isEmpty()) delay(1)
        }

        assertEquals(listOf("放箭"), seen)
        assertTrue(coordinator.acceptResponse(1))
        coordinator.close()
    }

    @Test
    fun `上屏后的联想候选不会清除表情查询`() = runBlocking {
        val seen = mutableListOf<String>()
        val coordinator = ExpressionQueryCoordinator(this, 0) { seen += it }

        coordinator.onCommitted("你好")
        delay(1)
        assertFalse(coordinator.onCandidatesChanged("呀", isAssociate = true))
        assertFalse(coordinator.onCandidatesChanged("啊", isAssociate = true))

        assertEquals(listOf("你好"), seen)
        assertTrue(coordinator.acceptResponse(1))
        coordinator.close()
    }

    @Test
    fun `组合态开始时通知调用方清理已提交图片结果`() = runBlocking {
        val coordinator = ExpressionQueryCoordinator(this, 0) { }

        coordinator.onCommitted("放箭")
        delay(1)

        assertTrue(coordinator.onComposingChanged("新输入"))
        assertFalse(coordinator.acceptResponse(1))
        coordinator.close()
    }

    @Test
    fun `关闭后取消待发布查询并拒绝响应`() = runBlocking {
        val seen = mutableListOf<String>()
        val coordinator = ExpressionQueryCoordinator(this, 30) { seen += it }

        coordinator.onCommitted("放箭")
        coordinator.close()
        delay(40)

        assertTrue(seen.isEmpty())
        assertFalse(coordinator.acceptResponse(1))
    }

    @Test
    fun `重置后相同提交词可以作为新查询再次发布`() = runBlocking {
        val seen = mutableListOf<String>()
        val coordinator = ExpressionQueryCoordinator(this, 0) { seen += it }

        coordinator.onCommitted("你好")
        withTimeout(1_000) {
            while (seen.size < 1) delay(1)
        }

        coordinator.reset()
        coordinator.onCommitted("你好")
        withTimeout(1_000) {
            while (seen.size < 2) delay(1)
        }

        assertEquals(listOf("你好", "你好"), seen)
        coordinator.close()
    }

    @Test
    fun `手动搜索取消防抖并立即发布最新查询`() = runBlocking {
        val seen = mutableListOf<String>()
        val coordinator = ExpressionQueryCoordinator(this, 10_000) { seen += it }

        coordinator.onCommitted("旧查询")
        assertTrue(coordinator.searchImmediately("新查询"))

        assertEquals(listOf("新查询"), seen)
        assertTrue(coordinator.acceptResponse(2))
        assertFalse(coordinator.acceptResponse(1))
        coordinator.close()
    }

    @Test
    fun `连续手动搜索使旧响应失效且关闭后不再发布`() = runBlocking {
        val seen = mutableListOf<String>()
        val coordinator = ExpressionQueryCoordinator(this, 0) { seen += it }

        assertTrue(coordinator.searchImmediately("第一次"))
        assertTrue(coordinator.searchImmediately("第二次"))
        assertFalse(coordinator.acceptResponse(1))
        assertTrue(coordinator.acceptResponse(2))
        coordinator.close()

        assertFalse(coordinator.searchImmediately("关闭后"))
        assertEquals(listOf("第一次", "第二次"), seen)
    }
}
