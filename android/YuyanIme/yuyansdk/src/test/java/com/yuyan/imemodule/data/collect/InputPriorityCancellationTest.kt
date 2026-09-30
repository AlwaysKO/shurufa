package com.yuyan.imemodule.data.collect

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.*
import org.junit.Test

class InputPriorityCancellationTest {
    @Test fun `触摸不在调用线程执行取消且合并排队任务`() {
        val work = mutableListOf<Runnable>()
        val cancelled = mutableListOf<String>()
        val gate = InputPriorityCancellation<String>(Executor { work.add(it) }, cancelled::add)
        gate.track("old", gate.token())
        repeat(100) { gate.request() }
        assertTrue(cancelled.isEmpty())
        assertEquals(1, work.size)
        work.removeAt(0).run()
        assertEquals(listOf("old"), cancelled)
    }
    @Test fun `排队的取消不能误伤输入之后新创建的请求`() {
        val work = mutableListOf<Runnable>()
        val cancelled = mutableListOf<String>()
        val gate = InputPriorityCancellation<String>(Executor { work.add(it) }, cancelled::add)
        gate.track("old", gate.token())
        gate.request()
        gate.track("new", gate.token())
        work.removeAt(0).run()
        assertEquals(listOf("old"), cancelled)
        gate.request()
        work.removeAt(0).run()
        assertEquals(listOf("old", "new"), cancelled)
    }
    @Test fun `已完成请求不会取消且过时代次注册立即拒绝`() {
        val work = mutableListOf<Runnable>()
        val cancelled = mutableListOf<String>()
        val gate = InputPriorityCancellation<String>(Executor { work.add(it) }, cancelled::add)
        val token = gate.token()
        gate.track("done", token)
        gate.finish("done")
        gate.request()
        gate.track("late", token)
        work.removeAt(0).run()
        assertEquals(listOf("late"), cancelled)
    }
    @Test fun `取消执行时再次输入不会丢掉新一代取消`() {
        val work = mutableListOf<Runnable>()
        val cancelled = mutableListOf<String>()
        lateinit var gate: InputPriorityCancellation<String>
        gate = InputPriorityCancellation(Executor { work.add(it) }) {
            cancelled.add(it)
            if (it == "first") { gate.track("second", gate.token()); gate.request() }
        }
        gate.track("first", gate.token())
        gate.request()
        while (work.isNotEmpty()) work.removeAt(0).run()
        assertEquals(listOf("first", "second"), cancelled)
    }
    @Test fun `调度拒绝不会打断输入且后续请求可以重试`() {
        var reject = true
        val work = mutableListOf<Runnable>()
        val cancelled = mutableListOf<String>()
        val gate = InputPriorityCancellation<String>(Executor {
            if (reject) throw RejectedExecutionException() else work.add(it)
        }, cancelled::add)
        gate.track("old", gate.token())
        gate.request()
        reject = false
        gate.request()
        work.removeAt(0).run()
        assertEquals(listOf("old"), cancelled)
    }
}
