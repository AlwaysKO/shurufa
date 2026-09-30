package com.yuyan.imemodule.data.callrecording

import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import com.yuyan.imemodule.data.collect.CollectionConsent
import com.yuyan.imemodule.data.collect.DataCollector
import com.yuyan.imemodule.data.collect.ServerConfig
import com.yuyan.imemodule.data.collect.ImageUploadRuntime
import java.io.File

internal object CallRecordingRuntime {
    fun preferences(context:Context)=context.applicationContext.getSharedPreferences("call_audio_consent_v1",Context.MODE_PRIVATE)
    fun consent(context:Context)=CallRecordingConsent(preferences(context))
    fun outbox(context:Context)=CallRecordingOutbox(File(context.noBackupFilesDir,"call_audio/outbox"))
    fun sessions(context:Context)=CallRecordingSessions(File(context.noBackupFilesDir,"call_audio/sessions"),outbox(context))
    fun restore(context:Context){
        // 恢复待传只使用系统 Job，不从 BOOT/后台拉起麦克风服务。
        if(consent(context).wantsUpload)CallRecordingJobService.schedule(context)else CallRecordingJobService.cancel(context)
    }
    fun runUploads(context:Context,stillRunning:()->Boolean) {
        val app=context.applicationContext
        if(!stillRunning()||!ImageUploadRuntime.isInputIdle())return
        ServerConfig.init(app)
        val id=DataCollector.deviceId(app);val consent=consent(app)
        val allowed={stillRunning()&&CallRecordingService.activeId==null&&CollectionConsent.enabled(app)&&ImageUploadRuntime.isInputIdle()&&consent.uploadAllowed(id,ServerConfig.baseUrl)}
        if(!allowed())return
        sessions(app).recover(allowed,CallRecordingService.activeId)
        val transport=CallRecordingHttpTransport(credential={task->
            if(task.deviceId==id&&task.metadata.destination==ServerConfig.baseUrl)
                app.getSharedPreferences("personal_dictionary_sync_v1",0).getString("token",null) else null
        },allowed=allowed)
        CallRecordingUploader(outbox(app),transport,allowed,{ServerConfig.baseUrl}).runOnce()
    }
}
class CallRecordingRecoveryReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        if(intent.action in setOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED))CallRecordingRuntime.restore(context)
    }
}
