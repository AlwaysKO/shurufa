package com.yuyan.imemodule.data.collect

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import java.util.ArrayDeque

/**
 * 有界、易失的 FIFO。offer 只表示内存接收，不表示已保存或已上报。
 * 使用进程级 scope；输入恢复应挂起等待，不应取消 worker。persist 必须幂等，失败会整项重试。
 */
internal class IdlePersistenceQueue(
    private val scope: CoroutineScope,
    private val awaitIdle: suspend () -> Unit,
    private val isIdle: () -> Boolean,
    private val maxEntries: Int = 256,
    private val maxBytes: Long = 4L * 1024 * 1024,
    private val retryDelay: suspend () -> Unit = { delay(1_000) },
    private val onFailure: (Exception) -> Unit = {},
) {
    private class Entry(val bytes: Long, val persist: suspend () -> Unit)
    private val lock = Any()
    private val entries = ArrayDeque<Entry>()
    private var bytes = 0L
    private var worker: Job? = null

    val pendingCount: Int get() = synchronized(lock) { entries.size }

    /** 预算包括正在写入的队首；队满拒绝新项，绝不在调用线程落盘兜底。 */
    fun offer(estimatedBytes: Long, persist: suspend () -> Unit): Boolean {
        val job = synchronized(lock) {
            if (estimatedBytes < 0 || entries.size >= maxEntries || estimatedBytes > maxBytes - bytes) return false
            entries.addLast(Entry(estimatedBytes, persist))
            bytes += estimatedBytes
            worker ?: newWorker().also { worker = it }
        }
        job.start()
        return true
    }

    private fun newWorker(): Job {
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = synchronized(lock) {
                        entries.peekFirst() ?: run { worker = null; return@launch }
                    }
                    awaitIdle()
                    currentCoroutineContext().ensureActive()
                    if (!isIdle()) continue
                    try {
                        // 不持有队列锁；已开始的事务允许结束，再输入时不启动下一项。
                        entry.persist()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        onFailure(error)
                        retryDelay()
                        continue
                    }
                    synchronized(lock) {
                        check(entries.removeFirst() === entry)
                        bytes -= entry.bytes
                    }
                }
            } finally {
                synchronized(lock) { if (worker === job) worker = null }
            }
        }
        return job
    }
}
