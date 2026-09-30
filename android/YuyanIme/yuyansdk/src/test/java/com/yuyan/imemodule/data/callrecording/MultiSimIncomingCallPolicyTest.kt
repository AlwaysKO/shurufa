package com.yuyan.imemodule.data.callrecording
import org.junit.Assert.*
import org.junit.Test

class MultiSimIncomingCallPolicyTest {
    private fun idle()=MultiSimIncomingCallPolicy(setOf(11,22)).apply{update(11,0);update(22,0)}
    @Test fun `两张卡分别来电均可开始重复事件不重复开始`() {
        for(id in listOf(11,22)){
            val p=idle();assertEquals(CallTransition.NONE,p.update(id,1))
            repeat(2){assertEquals(CallTransition.NONE,p.update(if(id==11)22 else 11,0))}
            assertEquals(CallTransition.START,p.update(id,2))
            assertEquals(CallTransition.NONE,p.update(id,2))
            assertEquals(CallTransition.NONE,p.update(if(id==11)22 else 11,0))
            assertEquals(CallTransition.END,p.update(id,0))
        }
    }
    @Test fun `另一卡来电或呼出立即中断直到全部空闲`() {
        for(state in listOf(1,2)){
            val p=idle();p.update(11,1);p.update(11,2)
            assertEquals(CallTransition.INTERRUPT,p.update(22,state))
            assertEquals(CallTransition.NONE,p.update(22,2))
            p.update(11,0);p.update(22,0)
            p.update(22,1);assertEquals(CallTransition.START,p.update(22,2))
        }
    }
    @Test fun `同卡呼叫等待中断且不重新启动`() {
        val p=idle();p.update(11,1);p.update(11,2)
        assertEquals(CallTransition.INTERRUPT,p.update(11,1))
        assertEquals(CallTransition.NONE,p.update(11,2))
    }
    @Test fun `未收到另一卡初始空闲不能录音`() {
        val p=MultiSimIncomingCallPolicy(setOf(11,22))
        assertEquals(CallTransition.NONE,p.update(11,0))
        assertEquals(CallTransition.NONE,p.update(11,1))
        assertEquals(CallTransition.NONE,p.update(11,2))
        assertEquals(CallTransition.NONE,p.update(22,0))
    }
    @Test fun `呼出拒接同时响铃未知订阅均不启动`() {
        for(events in listOf(listOf(11 to 2,11 to 0),listOf(11 to 1,11 to 0),
            listOf(11 to 1,22 to 1,11 to 2,22 to 0),listOf(99 to 1,99 to 2))){
            val p=idle();assertTrue(events.map{p.update(it.first,it.second)}.none{it==CallTransition.START})
        }
    }

    @Test fun `单卡退化保持来电序列且初始正在通话不开始`() {
        val p=MultiSimIncomingCallPolicy(setOf(11))
        assertEquals(CallTransition.NONE,p.update(11,2))
        p.update(11,0);p.update(11,1)
        assertEquals(CallTransition.START,p.update(11,2))
        assertEquals(CallTransition.END,p.update(11,0))
    }
}
