package com.yuyan.imemodule.data.callrecording
internal enum class CallTransition { NONE, START, END, INTERRUPT }
/** 不把 OFFHOOK 本身当接通。只支持已观察空闲后的单一来电，呼叫等待即停止。 */
internal class IncomingCallPolicy {
    private var idleObserved=false
    private var ringing=false
    private var active=false
    fun update(state:Int):CallTransition {
        if(state==0){val result=if(active)CallTransition.END else CallTransition.NONE;active=false;ringing=false;idleObserved=true;return result}
        if(state==1){
            if(active){active=false;ringing=false;idleObserved=false;return CallTransition.INTERRUPT}
            ringing=idleObserved;return CallTransition.NONE
        }
        if(state==2){
            if(active)return CallTransition.NONE
            val start=idleObserved&&ringing;ringing=false;idleObserved=false
            if(start){active=true;return CallTransition.START}
        }
        return CallTransition.NONE
    }
}
