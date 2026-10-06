package com.yuyan.imemodule.data.capture.net

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/** 新待办唤醒合并为一次；空闲时低频兜底，恢复重启或失败后保留的任务。 */
internal class CaptureWorkSignal {
    private val signal = Channel<Unit>(Channel.CONFLATED)

    fun wake() {
        signal.trySend(Unit)
    }

    suspend fun awaitNext(processed: Boolean, hasPending: Boolean = false) {
        val waitMillis = if (processed) 1_000L else if (hasPending) 60_000L else 30 * 60_000L
        withTimeoutOrNull(waitMillis) { signal.receive() }
    }
}
