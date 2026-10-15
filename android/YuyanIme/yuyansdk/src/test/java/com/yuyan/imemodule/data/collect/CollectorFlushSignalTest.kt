package com.yuyan.imemodule.data.collect

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(InternalCoroutinesApi::class)
class CollectorFlushSignalTest {
    @Test fun `空队列三十分钟才进行兜底检查`() {
        val clock=ManualDelay();var event:Boolean?=null
        val job=CoroutineScope(clock).launch{event=CollectorFlushSignal().awaitNext(false,false)}
        clock.advanceBy(1_799_999);assertFalse(job.isCompleted)
        clock.advanceBy(1);assertTrue(job.isCompleted);assertEquals(false,event)
    }
    @Test fun `有待办但输入网络或游戏守卫未放行每三十秒重查`() {
        val clock=ManualDelay();val job=CoroutineScope(clock).launch{CollectorFlushSignal().awaitNext(true,false)}
        clock.advanceBy(29_999);assertFalse(job.isCompleted)
        clock.advanceBy(1);assertTrue(job.isCompleted)
    }
    @Test fun `可上传图片一秒续传而不等待空闲周期`() {
        val clock=ManualDelay();val job=CoroutineScope(clock).launch{CollectorFlushSignal().awaitNext(true,true)}
        clock.advanceBy(999);assertFalse(job.isCompleted)
        clock.advanceBy(1);assertTrue(job.isCompleted)
    }
    @Test fun `新图片事件打断空闲等待并重算为一秒续传`() {
        val clock=ManualDelay();val signal=CollectorFlushSignal();var pending=false;var checks=0
        val job=CoroutineScope(clock).launch{
            while(true){checks++;if(!signal.awaitNext(pending,pending))break}
        }
        clock.advanceBy(600_000);assertEquals(1,checks)
        pending=true;signal.wake();assertEquals(2,checks);assertFalse(job.isCompleted)
        clock.advanceBy(999);assertFalse(job.isCompleted)
        clock.advanceBy(1);assertTrue(job.isCompleted)
    }
    @Test fun `上传结束信号重算为空队列后不自激活忙循环`() {
        val clock=ManualDelay();val signal=CollectorFlushSignal();var pending=true;var checks=0
        val job=CoroutineScope(clock).launch{
            while(true){checks++;if(!signal.awaitNext(pending,pending))break}
        }
        pending=false;repeat(100){signal.wake()}
        val settled=checks
        clock.advanceBy(1_799_999);assertEquals(settled,checks);assertFalse(job.isCompleted)
        clock.advanceBy(1);assertTrue(job.isCompleted)
    }
    @Test fun `等待取消后不会因为信号或超时触发上传`() {
        val clock=ManualDelay();val signal=CollectorFlushSignal();var continued=false
        val job=CoroutineScope(clock).launch{signal.awaitNext(false,false);continued=true}
        job.cancel();signal.wake();clock.advanceBy(1_800_000)
        assertFalse(continued);assertTrue(job.isCancelled)
    }
    @Test fun `普通待传按真实剩余到期待机而不是每三十秒唤醒`() {
        val clock=ManualDelay()
        val job=CoroutineScope(clock).launch{CollectorFlushSignal().awaitNext(true,false,240_000L)}
        clock.advanceBy(239_999);assertFalse(job.isCompleted)
        clock.advanceBy(1);assertTrue(job.isCompleted)
    }
    @Test fun `新事件可以打断五分钟批次等待`() {
        val clock=ManualDelay();val signal=CollectorFlushSignal();var event=false
        val job=CoroutineScope(clock).launch{event=signal.awaitNext(true,false,300_000L)}
        clock.advanceBy(60_000);signal.wake();assertTrue(job.isCompleted);assertTrue(event)
    }
    @Test fun `队列查询失败不会结束循环或每五秒自动重试`() {
        val clock=ManualDelay();val signal=CollectorFlushSignal();var attempts=0
        val errors=mutableListOf<Throwable>()
        val job=CoroutineScope(clock+CoroutineExceptionHandler { _,e -> errors+=e }).launch {
            while(true) signal.guardedCycle { attempts++;error("storage unavailable") }
        }
        clock.advanceBy(1_799_999)
        assertFalse(job.isCompleted);assertEquals(1,attempts);assertTrue(errors.isEmpty())
        clock.advanceBy(1);assertEquals(2,attempts)
        signal.wake();assertEquals(3,attempts)
        job.cancel()
    }
    @Test fun `轮询保护必须传播取消而不是等待三十分钟`() {
        val clock=ManualDelay();val job=CoroutineScope(clock).launch {
            CollectorFlushSignal().guardedCycle { throw CancellationException("stopped") }
        }
        assertTrue(job.isCancelled)
    }
    @Test fun `真实轮询入口在保护内读取待传元数据`() {
        val source=java.io.File("src/main/java/com/yuyan/imemodule/data/collect/DataCollector.kt").readText()
        val loop=source.substringAfter("flushJob = scope.launch").substringBefore("ensureLocationUpdates(app)")
        assertTrue(loop.contains("flushSignal.guardedCycle {"))
        assertTrue(loop.indexOf("flushSignal.guardedCycle {") < loop.indexOf("nextUploadDelay(app)"))
    }
    private class ManualDelay:CoroutineDispatcher(),Delay {
        var now=0L
        private val tasks=mutableListOf<Pair<Long,Runnable>>()
        override fun dispatch(context:CoroutineContext,block:Runnable)=block.run()
        override fun scheduleResumeAfterDelay(timeMillis:Long,continuation:CancellableContinuation<Unit>){
            val h=invokeOnTimeout(timeMillis,Runnable{continuation.resumeWith(Result.success(Unit))},continuation.context)
            continuation.invokeOnCancellation{h.dispose()}
        }
        override fun invokeOnTimeout(timeMillis:Long,block:Runnable,context:CoroutineContext):DisposableHandle {
            val task=now+timeMillis to block;tasks+=task
            return object:DisposableHandle{override fun dispose(){tasks.remove(task)}}
        }
        fun advanceBy(millis:Long){now+=millis;while(true){val task=tasks.filter{it.first<=now}.minByOrNull{it.first}?:return;tasks.remove(task);task.second.run()}}
    }
}
