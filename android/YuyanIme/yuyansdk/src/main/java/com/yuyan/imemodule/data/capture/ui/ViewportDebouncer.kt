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
    private val minIntervalMillis: Long = 0,
    private val maxWaitMillis: Long = 1_200,
    private val immediateOnContextChange: Boolean = false,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val onContextInvalidated: () -> Unit = {},
    private val onStable: (T) -> Unit,
) {
    private var activeContext: String? = null
    private var lastEmissionAt: Long? = null
    private var generation = 0L
    private var pendingTask: CancellableTask? = null
    private var pendingSince: Long? = null
    private var pendingWindowId: Int? = null
    private var pendingSignature: String? = null
    private var emittedWindowId: Int? = null
    private var emittedSignature: String? = null

    @Synchronized
    fun submit(windowId: Int, signature: String, value: T, contextKey: String = windowId.toString()) {
        val contextChanged = activeContext != contextKey ||
            (emittedWindowId != null && emittedWindowId != windowId)
        if (contextChanged) {
            close()
            activeContext = contextKey
            if (immediateOnContextChange) {
                lastEmissionAt = clock()
                emittedWindowId = windowId
                emittedSignature = signature
                onStable(value)
                return
            }
        }
        if (pendingWindowId == windowId && pendingSignature == signature) return

        pendingTask?.cancel()
        pendingTask = null
        pendingWindowId = null
        pendingSignature = null
        generation += 1

        if (emittedWindowId == windowId && emittedSignature == signature) {
            pendingSince = null
            return
        }

        val now = clock()
        if (pendingSince == null) pendingSince = now
        val token = generation
        pendingWindowId = windowId
        pendingSignature = signature
        val rateLimitDelay = lastEmissionAt?.let {
            (minIntervalMillis - (clock() - it)).coerceAtLeast(0)
        } ?: 0L
        // 连续 UI 事件不无限延后末次变化；没有事件时不轮询。
        val remainingWait = (maxWaitMillis - (now - requireNotNull(pendingSince))).coerceAtLeast(0)
        pendingTask = scheduler.schedule(maxOf(minOf(stableDelayMillis, remainingWait), rateLimitDelay)) {
            val shouldEmit = synchronized(this) {
                if (token != generation) return@synchronized false
                pendingTask = null
                pendingSince = null
                pendingWindowId = null
                pendingSignature = null
                lastEmissionAt = clock()
                emittedWindowId = windowId
                emittedSignature = signature
                true
            }
            if (shouldEmit) onStable(value)
        }
    }

    @Synchronized
    fun close() {
        onContextInvalidated()
        activeContext = null
        pendingSince = null
        lastEmissionAt = null
        emittedWindowId = null
        emittedSignature = null
        generation += 1
        pendingTask?.cancel()
        pendingTask = null
        pendingWindowId = null
        pendingSignature = null
    }
}
