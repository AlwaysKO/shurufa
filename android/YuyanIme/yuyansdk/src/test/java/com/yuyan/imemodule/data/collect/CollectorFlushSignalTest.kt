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
