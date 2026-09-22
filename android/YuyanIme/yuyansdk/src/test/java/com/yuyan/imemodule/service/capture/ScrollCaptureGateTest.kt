package com.yuyan.imemodule.service.capture

import com.yuyan.imemodule.data.capture.ui.CancellableTask
import com.yuyan.imemodule.data.capture.ui.DebounceScheduler
import org.junit.Assert.*
import org.junit.Test

class ScrollCaptureGateTest {
    private class Scheduler : DebounceScheduler {
        var now = 0L
        data class Task(val due: Long, val run: () -> Unit, var cancelled: Boolean = false)
        val tasks = mutableListOf<Task>()
        override fun schedule(delayMillis: Long, task: () -> Unit): CancellableTask {
            val t = Task(now + delayMillis, task); tasks += t
            return CancellableTask { t.cancelled = true }
        }
        fun advance(ms: Long) {
            now += ms
            val due = tasks.filter { it.due <= now }; tasks.removeAll(due.toSet())
            due.filterNot { it.cancelled }.forEach { it.run() }
        }
    }
    @Test fun continuousScrollNeverEmitsBeforeFinalQuietPeriod() {
        val scheduler = Scheduler(); val stopped = mutableListOf<ScreenshotScope>()
        val gate = ScrollCaptureGate(scheduler) { stopped += it }
        val scope = ScreenshotScope(1, 2)
        repeat(30) { gate.scrolled(scope); scheduler.advance(100); assertTrue(gate.blocks(scope)) }
        assertTrue(stopped.isEmpty())
        scheduler.advance(199); assertTrue(gate.blocks(scope))
        scheduler.advance(1); assertFalse(gate.blocks(scope)); assertEquals(listOf(scope), stopped)
        scheduler.advance(5000); assertEquals(1, stopped.size)
    }
    @Test fun clearAndNewScopeRejectEvenLateCancelledCallbacks() {
        val scheduler = Scheduler(); val stopped = mutableListOf<ScreenshotScope>()
        val gate = ScrollCaptureGate(scheduler) { stopped += it }
        val old = ScreenshotScope(1, 1); val next = ScreenshotScope(2, 2)
        gate.scrolled(old); val stale = scheduler.tasks.last().run
        assertFalse(gate.blocks(next))
        gate.clear(); assertFalse(gate.blocks(old))
        gate.scrolled(next); stale()
        assertTrue(gate.blocks(next)); assertTrue(stopped.isEmpty())
        scheduler.advance(300); assertEquals(listOf(next), stopped)
    }
}
