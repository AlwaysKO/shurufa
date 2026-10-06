package com.yuyan.imemodule.data.capture.net

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(InternalCoroutinesApi::class)
class CaptureWorkSignalTest {
    @Test fun pendingWorkPausedByInputRetriesWithoutAnotherEvent() {
        val clock = ManualDelay()
        val signal = CaptureWorkSignal()
        var allowed = false
        var pending = true
        val worker = CoroutineScope(clock).launch {
            while (pending) {
                if (allowed) { pending = false; break }
                signal.awaitNext(processed = false, hasPending = pending)
            }
        }
        signal.wake() // 新任务在输入时到达；唤醒被消费后仍需保留恢复检查。
        allowed = true
        clock.advanceBy(59_999)
        assertFalse(worker.isCompleted)
        clock.advanceBy(1)
        assertTrue("existing work must resume without waiting thirty minutes or a new notification", worker.isCompleted)
        assertFalse(pending)
    }

    @Test fun idleWorkerDoesNotWakeUntilThirtyMinuteFallback() {
        val clock = ManualDelay()
        val signal = CaptureWorkSignal()
        val job = CoroutineScope(clock).launch { signal.awaitNext(processed = false) }
        clock.advanceBy(1_799_999)
        assertFalse("empty queues must wait thirty minutes without a new event", job.isCompleted)
        clock.advanceBy(1)
        assertTrue("fallback must recover persisted work without a new event", job.isCompleted)
    }

    @Test fun successfulBatchContinuesAfterOneSecond() {
        val clock = ManualDelay()
        val job = CoroutineScope(clock).launch { CaptureWorkSignal().awaitNext(processed = true) }
        clock.advanceBy(999)
        assertFalse(job.isCompleted)
        clock.advanceBy(1)
        assertTrue(job.isCompleted)
    }

    @Test fun newWorkInterruptsIdleWaitWithoutAdvancingTime() {
        val clock = ManualDelay()
        val signal = CaptureWorkSignal()
        val job = CoroutineScope(clock).launch { signal.awaitNext(processed = false) }
        signal.wake()
        assertTrue(job.isCompleted)
        assertEquals(0L, clock.now)
    }

    @Test fun wakeBeforeWaitIsRetainedAndBurstsAreCoalesced() {
        val clock = ManualDelay()
        val signal = CaptureWorkSignal()
        repeat(100) { signal.wake() }
        val first = CoroutineScope(clock).launch { signal.awaitNext(processed = false) }
        assertTrue("enqueue during processing must not lose its wakeup", first.isCompleted)
        val second = CoroutineScope(clock).launch { signal.awaitNext(processed = false) }
        assertFalse("a burst must not create a polling backlog", second.isCompleted)
        second.cancel()
    }

    @Test fun serviceCancellationStopsWaitingWithoutReplaying() {
        val clock = ManualDelay()
        var resumed = false
        val job = CoroutineScope(clock).launch {
            CaptureWorkSignal().awaitNext(processed = false)
            resumed = true
        }
        job.cancel()
        clock.advanceBy(1_800_000)
        assertTrue(job.isCancelled)
        assertFalse(resumed)
    }

    private class ManualDelay : CoroutineDispatcher(), Delay {
        var now = 0L
            private set
        private val tasks = mutableListOf<Pair<Long, Runnable>>()
        override fun dispatch(context: CoroutineContext, block: Runnable) = block.run()
        override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
            val handle = invokeOnTimeout(timeMillis, Runnable { continuation.resumeWith(Result.success(Unit)) }, continuation.context)
            continuation.invokeOnCancellation { handle.dispose() }
        }
        override fun invokeOnTimeout(timeMillis: Long, block: Runnable, context: CoroutineContext): DisposableHandle {
            val task = now + timeMillis to block
            tasks += task
            return object : DisposableHandle { override fun dispose() { tasks.remove(task) } }
        }
        fun advanceBy(millis: Long) {
            now += millis
            while (true) {
                val task = tasks.filter { it.first <= now }.minByOrNull { it.first } ?: return
                tasks.remove(task)
                task.second.run()
            }
        }
    }
}
