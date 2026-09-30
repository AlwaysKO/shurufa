package com.yuyan.imemodule.data.calllog

import android.content.SharedPreferences
import com.yuyan.imemodule.data.callrecording.CallRecordingOutbox

internal class PhoneCallLogConsent(private val prefs:SharedPreferences) {
    val enabled:Boolean get()=prefs.getBoolean("enabled",false)
    val revision:Long get()=prefs.getLong("revision",0)
    fun allowed(device:String,target:String)=enabled&&prefs.getString("device",null)==device&&
        prefs.getString("destination",null)==target&&CallRecordingOutbox.validDestination(target)
    fun grant(device:String,target:String,ticket:Long):Boolean=synchronized(lock){
        if(revision!=ticket)return@synchronized false
        require(CallRecordingOutbox.validId(device)&&CallRecordingOutbox.validDestination(target))
        check(prefs.edit().putBoolean("enabled",true).putString("device",device).putString("destination",target)
            .putLong("revision",revision+1).commit());true
    }
    fun revoke()=synchronized(lock){prefs.edit().putBoolean("enabled",false).putLong("revision",revision+1).apply()}
    private companion object{val lock=Any()}
}
