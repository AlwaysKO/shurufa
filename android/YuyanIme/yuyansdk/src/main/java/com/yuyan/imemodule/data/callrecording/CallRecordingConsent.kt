package com.yuyan.imemodule.data.callrecording
import android.content.SharedPreferences
internal class CallRecordingConsent(private val prefs:SharedPreferences) {
    val wantsRecording:Boolean get()=prefs.getBoolean("record",false)
    val wantsUpload:Boolean get()=prefs.getBoolean("upload",false)
    val destination:String get()=prefs.getString("destination","")?:""
    private fun matches(device:String,target:String)=prefs.getString("version",null)=="call-audio-v1" && prefs.getString("device",null)==device && destination==target && CallRecordingOutbox.validDestination(target)
    fun recordingAllowed(device:String,target:String)=wantsRecording&&matches(device,target)
    val hasExpandedRecordingScope:Boolean get()=prefs.getString("recording_scope",null)=="phone_wechat_v2"
    fun expandedRecordingAllowed(device:String,target:String)=recordingAllowed(device,target)&&hasExpandedRecordingScope
    fun uploadAllowed(device:String,target:String)=wantsUpload&&matches(device,target)
    fun needsCombinedConfirmation(device:String,target:String)=
        !matches(device,target)||!prefs.getBoolean("combined_accepted",false)||!hasExpandedRecordingScope
    val revision:Long get()=prefs.getLong("revision",0)
    fun grantCombined(device:String,target:String,expectedRevision:Long=revision):Boolean=synchronized(lock){
        if(revision!=expectedRevision)return@synchronized false
        save(device,target,true,true,true)
        true
    }
    /** 调用方在 IO 线程提交；只有显式同意才更改绑定，不静默迁移授权。 */
    fun grant(device:String,target:String,record:Boolean,upload:Boolean)=synchronized(lock) {
        save(device,target,record,upload,false)
    }
    private fun save(device:String,target:String,record:Boolean,upload:Boolean,combined:Boolean) {
        require(CallRecordingOutbox.validId(device)&&CallRecordingOutbox.validDestination(target))
        check(prefs.edit().putString("version","call-audio-v1").putString("device",device).putString("destination",target)
            .putString("recording_scope",if(combined)"phone_wechat_v2" else null)
            .putLong("revision",revision+1).putBoolean("combined_accepted",combined).putBoolean("record",record).putBoolean("upload",upload).commit())
    }
    fun revokeRecording()=revoke(record=true,upload=false)
    fun revokeUpload()=revoke(record=false,upload=true)
    fun revokeAll()=revoke(record=true,upload=true)
    private fun revoke(record:Boolean,upload:Boolean)=synchronized(lock){
        val edit=prefs.edit().putLong("revision",revision+1)
        if(record)edit.putBoolean("record",false)
        if(upload)edit.putBoolean("upload",false)
        edit.apply()
    }
    private companion object { val lock=Any() }
}
