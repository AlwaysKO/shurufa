package com.yuyan.imemodule.data.collect

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** 热路径只更新代次并合并调度；网络资源关闭在 executor 上执行。 */
internal class InputPriorityCancellation<T : Any>(
    private val executor: Executor,
    private val cancel: (T) -> Unit,
) {
    private val generation = AtomicLong()
    private val scheduled = AtomicBoolean()
    private val calls = ConcurrentHashMap<T, Long>()

    fun token(): Long = generation.get()

    /** 只能从后台的请求准备路径调用。注册与输入并发时，过期请求仍必须取消。 */
    fun track(call: T, token: Long) {
        calls[call] = token
        if (token != generation.get() && calls.remove(call, token)) cancel(call)
    }

    fun finish(call: T) { calls.remove(call) }

    fun request() {
        generation.incrementAndGet()
        schedule()
    }

    private fun schedule() {
        if (!scheduled.compareAndSet(false, true)) return
        try {
            executor.execute {
                val cutoff = generation.get()
                try {
                    calls.forEach { (call, token) ->
                        if (token < cutoff && calls.remove(call, token)) {
                            try { cancel(call) } catch (_: RuntimeException) { /* 其他请求仍须释放。 */ }
                        }
                    }
                } finally {
                    scheduled.set(false)
                    if (generation.get() != cutoff) schedule()
                }
            }
        } catch (_: RejectedExecutionException) {
            scheduled.set(false) // 代次已经失效；流式写入门禁仍然关闭，下次输入可重试调度。
        }
    }
}
