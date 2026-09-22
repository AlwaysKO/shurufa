package com.yuyan.imemodule.data.collect

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.CoroutineContext

class TargetDeliveryTasksTest {
    private class QueuedDispatcher : CoroutineDispatcher() {
        private val work = java.util.ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { work.add(block) }
        fun drain() { while (work.isNotEmpty()) work.removeFirst().run() }
    }
    @Test fun `启动前取消不遗留占用下一轮仍能发送`() = runBlocking {
        val dispatcher = QueuedDispatcher(); val tasks = TargetDeliveryTasks(dispatcher)
        var sent = 0
        val first = launch(start = CoroutineStart.UNDISPATCHED) { tasks.run(listOf("local")) { sent++ } }
        first.cancel(); dispatcher.drain(); first.join()
        val retry = launch(start = CoroutineStart.UNDISPATCHED) { tasks.run(listOf("local")) { sent++ } }
        dispatcher.drain(); retry.join()
        assertEquals(1, sent)
    }
    @Test fun `本机目标异常不能取消线上任务`() = runBlocking {
        val dispatcher = QueuedDispatcher(); val tasks = TargetDeliveryTasks(dispatcher)
        val sent = mutableListOf<String>(); val failed = mutableListOf<String>()
        var propagated: Throwable? = null
        val run = launch(start = CoroutineStart.UNDISPATCHED) {
            try { tasks.run(listOf("local", "online"), onFailure = { target, _ -> failed.add(target) }) {
                if (it == "local") error("simulated failure") else sent.add(it)
            } } catch (error: Throwable) { propagated = error }
        }
        dispatcher.drain(); run.join()
        assertNull(propagated); assertEquals(listOf("online"), sent); assertEquals(listOf("local"), failed)
    }
    @Test fun `父取消释放两个目标且同目标并发不重复执行`() = runBlocking {
        val dispatcher = QueuedDispatcher(); val tasks = TargetDeliveryTasks(dispatcher)
        val entered = mutableListOf<String>()
        val first = launch(start = CoroutineStart.UNDISPATCHED) { tasks.run(listOf("local", "online")) {
            entered.add(it); awaitCancellation()
        } }
        dispatcher.drain()
        val second = launch(start = CoroutineStart.UNDISPATCHED) { tasks.run(listOf("local", "online")) { error("duplicate") } }
        dispatcher.drain(); second.join()
        assertEquals(2, entered.size)
        first.cancel(); dispatcher.drain(); first.join()
        val retry = launch(start = CoroutineStart.UNDISPATCHED) { tasks.run(listOf("local", "online")) { entered.add(it) } }
        dispatcher.drain(); retry.join(); assertEquals(4, entered.size)
    }
}
