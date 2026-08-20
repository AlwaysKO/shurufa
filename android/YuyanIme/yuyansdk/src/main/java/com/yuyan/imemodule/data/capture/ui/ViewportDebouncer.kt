package com.yuyan.imemodule.data.capture.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun interface CancellableTask {
    fun cancel()
}

fun interface DebounceScheduler {
    fun schedule(delayMillis: Long, task: () -> Unit): CancellableTask
}

class CoroutineDebounceScheduler(
    private val scope: CoroutineScope,
) : DebounceScheduler {
    override fun schedule(delayMillis: Long, task: () -> Unit): CancellableTask {
        val job: Job = scope.launch {
            delay(delayMillis)
            task()
        }
        return CancellableTask(job::cancel)
    }
}

class ViewportDebouncer<T>(
    private val scheduler: DebounceScheduler,
    private val stableDelayMillis: Long = 300,
    private val onStable: (T) -> Unit,
) {
    private var generation = 0L
    private var pendingTask: CancellableTask? = null
    private var pendingWindowId: Int? = null
    private var pendingSignature: String? = null
    private var emittedWindowId: Int? = null
    private var emittedSignature: String? = null

    @Synchronized
    fun submit(windowId: Int, signature: String, value: T) {
        if (pendingWindowId == windowId && pendingSignature == signature) return

        pendingTask?.cancel()
        pendingTask = null
        pendingWindowId = null
        pendingSignature = null
        generation += 1

        if (emittedWindowId == windowId && emittedSignature == signature) return

        val token = generation
        pendingWindowId = windowId
        pendingSignature = signature
        pendingTask = scheduler.schedule(stableDelayMillis) {
            val shouldEmit = synchronized(this) {
                if (token != generation) return@synchronized false
                pendingTask = null
                pendingWindowId = null
                pendingSignature = null
                emittedWindowId = windowId
                emittedSignature = signature
                true
            }
            if (shouldEmit) onStable(value)
        }
    }

    @Synchronized
    fun close() {
        generation += 1
        pendingTask?.cancel()
        pendingTask = null
        pendingWindowId = null
        pendingSignature = null
    }
}
