package com.yuyan.imemodule.data.callrecording
import org.junit.Assert.*
import org.junit.Test
class IncomingCallPolicyTest {
    @Test fun `只有已观察空闲后的来电接通可以开始`() {
        val p=IncomingCallPolicy()
        assertEquals(CallTransition.NONE,p.update(0))
        assertEquals(CallTransition.NONE,p.update(1))
        assertEquals(CallTransition.START,p.update(2))
        assertEquals(CallTransition.NONE,p.update(2))
        assertEquals(CallTransition.END,p.update(0))
    }
    @Test fun `初始响铃或通话状态以及呼出拒接均不录`() {
        for(states in listOf(listOf(1,2,0),listOf(2,0),listOf(0,2,0),listOf(0,1,0))) {
            val p=IncomingCallPolicy();assertTrue(states.map(p::update).none{it==CallTransition.START})
        }
    }
    @Test fun `通话等待中断原录音且空闲前不重新开始`() {
        val p=IncomingCallPolicy();listOf(0,1,2).forEach(p::update)
        assertEquals(CallTransition.INTERRUPT,p.update(1))
        assertEquals(CallTransition.NONE,p.update(2))
        p.update(0);p.update(1);assertEquals(CallTransition.START,p.update(2))
    }
}
