package com.yuyan.imemodule.data.collect

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class IdlePersistenceQueueTest {
    @Test fun `忙时只暂存且空闲后按接收顺序写入`() = runBlocking {
        val idle = CompletableDeferred<Unit>()
        val written = mutableListOf<Int>()
        val queue = IdlePersistenceQueue(this, { idle.await() }, { idle.isCompleted })
        repeat(100) { assertTrue(queue.offer(1) { written += it }) }
        yield()
        assertTrue(written.isEmpty())
        idle.complete(Unit)
        yield()
        assertEquals((0..99).toList(), written)
    }

    @Test fun `恢复打字后不启动下一项`() = runBlocking {
        var idle = true
        val resume = CompletableDeferred<Unit>()
        val written = mutableListOf<Int>()
        val queue = IdlePersistenceQueue(this, { if (!idle) resume.await() }, { idle })
        queue.offer(1) { written += 1; idle = false }
        queue.offer(1) { written += 2 }
        yield()
        assertEquals(listOf(1), written)
        idle = true
        resume.complete(Unit)
        yield()
        assertEquals(listOf(1, 2), written)
    }

    @Test fun `失败保留队首且重试前不越过失败项`() = runBlocking {
        val retry = CompletableDeferred<Unit>()
        val attempts = mutableListOf<Int>()
        var failed = false
        val queue = IdlePersistenceQueue(this, {}, { true }, retryDelay = { retry.await() })
        queue.offer(1) {
            attempts += 1
            if (!failed) { failed = true; throw IllegalStateException("retry") }
        }
        queue.offer(1) { attempts += 2 }
        yield()
        assertEquals(listOf(1), attempts)
        retry.complete(Unit)
        yield()
        assertEquals(listOf(1, 1, 2), attempts)
    }

    @Test fun `按数量和内存预算拒绝新项但不覆盖旧项`() = runBlocking {
        val idle = CompletableDeferred<Unit>()
        val written = mutableListOf<Int>()
        val queue = IdlePersistenceQueue(this, { idle.await() }, { true }, maxEntries = 2, maxBytes = 10)
        assertTrue(queue.offer(6) { written += 1 })
        assertFalse(queue.offer(5) { fail("超内存项不可执行") })
        assertTrue(queue.offer(4) { written += 2 })
        assertFalse(queue.offer(0) { fail("超数量项不可执行") })
        idle.complete(Unit)
        yield()
        assertEquals(listOf(1, 2), written)
        assertTrue(queue.offer(10) { written += 3 })
        yield()
        assertEquals(listOf(1, 2, 3), written)
    }

    @Test fun `空闲等待返回后仍需复核忙闲`() = runBlocking {
        var checks = 0
        var waits = 0
        var writes = 0
        val queue = IdlePersistenceQueue(this, { waits++ }, { ++checks > 1 })
        queue.offer(1) { writes++ }
        yield()
        assertEquals(2, waits)
        assertEquals(1, writes)
    }
    @Test fun `写入未结束也能接收新项且在途项仍占预算`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val written = mutableListOf<Int>()
        val queue = IdlePersistenceQueue(this, {}, { true }, maxEntries = 2)
        assertTrue(queue.offer(1) { started.complete(Unit); finish.await(); written += 1 })
        started.await()
        assertTrue(queue.offer(1) { written += 2 })
        assertFalse(queue.offer(1) { fail("在途项未释放预算") })
        finish.complete(Unit)
        yield()
        assertEquals(listOf(1, 2), written)
        assertEquals(0, queue.pendingCount)
    }

}
