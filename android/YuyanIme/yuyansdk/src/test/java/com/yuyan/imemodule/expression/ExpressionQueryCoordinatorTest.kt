package com.yuyan.imemodule.expression

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpressionQueryCoordinatorTest {
    @Test
    fun `只发布防抖后的最新候选`() = runBlocking {
        val seen = mutableListOf<String>()
        val coordinator = ExpressionQueryCoordinator(this, 150) { seen += it }

        coordinator.onFirstCandidate("放")
        delay(100)
        coordinator.onFirstCandidate("放箭")
        delay(170)

        assertEquals(listOf("放箭"), seen)
        coordinator.close()
    }

    @Test
    fun `候选变化后丢弃过期响应`() = runBlocking {
        val coordinator = ExpressionQueryCoordinator(this, 30) { }

        coordinator.onFirstCandidate("旧候选")
        delay(40)
        assertTrue(coordinator.acceptResponse(1))

        coordinator.onFirstCandidate("新候选")
        assertFalse(coordinator.acceptResponse(1))
        delay(40)
        assertTrue(coordinator.acceptResponse(2))
        coordinator.close()
    }

    @Test
    fun `候选上屏后保留最后查询词`() = runBlocking {
        val seen = mutableListOf<String>()
        val coordinator = ExpressionQueryCoordinator(this, 30) { seen += it }

        coordinator.onFirstCandidate("放箭")
        delay(40)
        coordinator.onCommitted("放箭")
        coordinator.onFirstCandidate(null)
        delay(40)

        assertEquals(listOf("放箭"), seen)
        assertTrue(coordinator.acceptResponse(1))
        coordinator.close()
    }

    @Test
    fun `关闭后取消待发布查询并拒绝响应`() = runBlocking {
        val seen = mutableListOf<String>()
        val coordinator = ExpressionQueryCoordinator(this, 30) { seen += it }

        coordinator.onFirstCandidate("放箭")
        coordinator.close()
        delay(40)

        assertTrue(seen.isEmpty())
        assertFalse(coordinator.acceptResponse(1))
    }
}
