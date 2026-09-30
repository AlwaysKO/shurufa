package com.yuyan.imemodule.data.collect

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/** 每个采集入口只保留一个空闲等待者；已经开始的工作不被后续探测取消。 */
internal class LatestIdleWork(
    private val scope: CoroutineScope,
    private val awaitIdle: suspend (() -> Boolean) -> Boolean,
) {
    private val pending = AtomicReference<Job?>()

    fun submit(isCurrent: () -> Boolean, work: suspend () -> Unit): Job {
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                if (!awaitIdle { pending.get() === job && isCurrent() }) return@launch
                if (!pending.compareAndSet(job, null) || !isCurrent()) return@launch
                work()
            } finally { pending.compareAndSet(job, null) }
        }
        pending.getAndSet(job)?.cancel()
        job.start()
        return job
    }

    fun cancel() { pending.getAndSet(null)?.cancel() }
}
