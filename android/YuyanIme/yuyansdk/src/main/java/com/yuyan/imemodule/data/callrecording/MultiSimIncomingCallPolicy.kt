package com.yuyan.imemodule.data.callrecording

/** 每张卡须先确认空闲。只录单个来电会话，任何并发通话均中断至全部空闲。 */
internal class MultiSimIncomingCallPolicy(subscriptions:Set<Int>) {
    private val states=subscriptions.associateWith<Int,Int?>{null}.toMutableMap()
    private var blocked=true
    private var ringing:Int?=null
    private var active:Int?=null
    init { require(subscriptions.isNotEmpty()) }
    fun update(subscription:Int,state:Int):CallTransition {
        if(subscription !in states||state !in 0..2)return CallTransition.NONE
        states[subscription]=state
        if(states.values.all{it==0}){
            val result=if(active!=null)CallTransition.END else CallTransition.NONE
            active=null;ringing=null;blocked=false
            return result
        }
        if(blocked)return CallTransition.NONE
        if(active!=null){
            if((subscription!=active&&state!=0)||(subscription==active&&state==1)){
                active=null;ringing=null;blocked=true
                return CallTransition.INTERRUPT
            }
            return CallTransition.NONE
        }
        val othersIdle=states.all{it.key==subscription||it.value==0}
        if(state==1&&othersIdle){ringing=subscription;return CallTransition.NONE}
        if(state==2&&ringing==subscription&&othersIdle){
            active=subscription;ringing=null
            return CallTransition.START
        }
        // 另一张空闲卡的重复回调不得丢失正在响铃的候选会话。
        if(state==0&&ringing!=null&&states.all{it.key==ringing||it.value==0})return CallTransition.NONE
        blocked=true;ringing=null
        return CallTransition.NONE
    }
}
