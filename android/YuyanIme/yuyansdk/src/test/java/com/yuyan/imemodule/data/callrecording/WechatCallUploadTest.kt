package com.yuyan.imemodule.data.callrecording

import org.junit.Assert.*
import org.junit.Test

class WechatCallUploadTest {
    private class Fixture {
        var now=100_000L
        var allowed=true
        var attempts=0
        var followUp:Long?=null
        var duringUpload:(()->Unit)?=null
        val retries=mutableListOf<Long>()
        val queued=mutableListOf<Pair<Long,()->Unit>>()
        val dispatch=WechatCallUploadDispatch(
            now={now},schedule={delay,block->queued.add(now+delay to block)},
            allowed={allowed},upload={attempts++;duringUpload?.invoke();35_000L},
            followUp={followUp},retry={retries.add(it)})
        fun advance(ms:Long=0){now+=ms;while(true){
            val next=queued.filter{it.first<=now}.minByOrNull{it.first}?:return
            queued.remove(next);next.second()
        }}
    }
    @Test fun `结束直接派发并合并同一轮重复事件`() {
        val f=Fixture();repeat(10){f.dispatch.request()}
        assertEquals(0,f.attempts);f.advance()
        assertEquals(1,f.attempts);assertEquals(listOf(35_000L),f.retries)
    }
    @Test fun `执行期间新事件不会丢失或并行上传`() {
        val f=Fixture();f.duringUpload={f.duringUpload=null;repeat(5){f.dispatch.request()}}
        f.dispatch.request();f.advance();assertEquals(2,f.attempts)
    }
    @Test fun `文件延迟发布短时复查新事件能提前唤醒到期任务`() {
        val f=Fixture();f.followUp=2000
        f.dispatch.request();f.advance();assertEquals(1,f.attempts)
        f.advance(1000);assertEquals(1,f.attempts)
        f.dispatch.request();f.advance();assertEquals(2,f.attempts)
        f.followUp=null;f.advance(2000);assertEquals(3,f.attempts)
        f.advance(60000);assertEquals(3,f.attempts)
    }
    @Test fun `断网和撤销不执行上传取消使已排队回调失效`() {
        val f=Fixture();f.allowed=false;f.dispatch.request();f.advance();assertEquals(0,f.attempts)
        f.allowed=true;f.dispatch.request();f.dispatch.cancel();f.advance();assertEquals(0,f.attempts)
    }
    @Test fun `上传异常仍预约恢复且后续事件仍可执行`() {
        val f=Fixture();f.duringUpload={throw java.io.IOException("temporary")}
        f.dispatch.request();f.advance();assertEquals(listOf(35_000L),f.retries)
        f.duringUpload=null;f.dispatch.request();f.advance();assertEquals(2,f.attempts)
    }
    @Test fun `微信语音视频共享一分钟以内有界追查窗口`() {
        assertEquals(2000L,wechatCallUploadFollowUp(100000,100000))
        assertEquals(5000L,wechatCallUploadFollowUp(100000,106000))
        assertEquals(15000L,wechatCallUploadFollowUp(100000,120000))
        assertNull(wechatCallUploadFollowUp(100000,160001))
        assertNull(wechatCallUploadFollowUp(100000,99999))
        assertNull(wechatCallUploadFollowUp(null,100000))
    }
}
