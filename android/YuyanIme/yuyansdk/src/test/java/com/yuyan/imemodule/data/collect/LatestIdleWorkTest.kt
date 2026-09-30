package com.yuyan.imemodule.data.collect

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class LatestIdleWorkTest {
    @Test fun `持续输入期间只保留最新等待请求且空闲后只执行一次`() = runBlocking {
        val idle = CompletableDeferred<Unit>()
        val queue = LatestIdleWork(this) { current -> idle.await(); current() }
        val calls = mutableListOf<Int>()
        val jobs = mutableListOf<Job>()
        repeat(100) { index ->
            jobs += queue.submit({ true }) { calls += index }
            yield()
        }
        assertTrue(calls.isEmpty())
        assertTrue(jobs.dropLast(1).all { it.isCancelled })
        idle.complete(Unit)
        jobs.last().join()
        assertEquals(listOf(99), calls)
    }
    @Test fun `切换身份后不执行旧工作`() = runBlocking {
        var current = true
        val idle = CompletableDeferred<Unit>()
        val queue = LatestIdleWork(this) { valid -> idle.await(); valid() }
        var called = false
        val job = queue.submit({ current }) { called = true }
        yield()
        current = false
        idle.complete(Unit)
        job.join()
        assertFalse(called)
    }
    @Test fun `新请求不取消已经开始的首个视口工作`() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val queue = LatestIdleWork(this) { valid -> valid() }
        val first = queue.submit({ true }) { started.complete(Unit); release.await() }
        started.await()
        val second = queue.submit({ true }) {}
        second.join()
        assertTrue(first.isActive)
        release.complete(Unit)
        first.join()
        assertFalse(first.isCancelled)
    }
    @Test fun `取消等待释放旧任务且允许下次提交`() = runBlocking {
        val idle = CompletableDeferred<Unit>()
        val queue = LatestIdleWork(this) { valid -> idle.await(); valid() }
        var count = 0
        val old = queue.submit({ true }) { count++ }
        yield()
        queue.cancel()
        old.join()
        assertTrue(old.isCancelled)
        val next = queue.submit({ true }) { count++ }
        idle.complete(Unit)
        next.join()
        assertEquals(1, count)
    }
}
