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
    // Android 可将系统私有根目录映射为 /data/data；仅归一化系统给出的根，子目录仍拒绝链接。
    private fun root(context:Context)=File(context.noBackupFilesDir.canonicalFile,"call_audio")
    fun outbox(context:Context)=CallRecordingOutbox(File(root(context),"outbox"))
    fun sessions(context:Context)=CallRecordingSessions(File(root(context),"sessions"),outbox(context))
    fun restore(context:Context){
        com.yuyan.imemodule.data.calllog.PhoneCallLogRuntime.restore(context)
        // 恢复待传只使用系统 Job，不从 BOOT/后台拉起麦克风服务。
        if(consent(context).wantsUpload)CallRecordingJobService.schedule(context)else CallRecordingJobService.cancel(context)
    }
    fun runUploads(context:Context,stillRunning:()->Boolean):Boolean {
        val app=context.applicationContext
        if(!stillRunning()||!ImageUploadRuntime.isInputIdle())return false
        ServerConfig.init(app)
        val id=DataCollector.deviceId(app);val consent=consent(app)
        val allowed={stillRunning()&&CallRecordingService.activeId==null&&CollectionConsent.enabled(app)&&ImageUploadRuntime.isInputIdle()&&consent.uploadAllowed(id,ServerConfig.baseUrl)}
        if(!allowed())return false
        sessions(app).recover(allowed,CallRecordingService.activeId)
        val transport=CallRecordingHttpTransport(credential={task->
            if(task.deviceId==id&&task.metadata.destination==ServerConfig.baseUrl)
                app.getSharedPreferences("personal_dictionary_sync_v1",0).getString("token",null) else null
        },allowed=allowed)
        val box=outbox(app)
        var retrySoon=false
        // 周期与即时 Job 共享批次锁；不让两个扫描器为同一原件创建两个任务。
        box.uploadBatch {
            val documents=SystemRecordingDocuments(app)
            val importer=SystemRecordingImporter(File(root(app),"system_index"),box)
            val scanned=mutableMapOf<String,Boolean>()
            for(platform in listOf("phone","wechat")) {
                if(documents.tree(platform)==null)continue
                try {
                    val result=importer.scan(documents.list(platform,allowed),id,ServerConfig.baseUrl,allowed,documents::open,documents::unchanged)
                    scanned[platform]=result.finished && result.waiting==0
                    preferences(app).edit().putString("system_scan_$platform",
                        "本次入队 ${result.imported}；等待文件写完 ${result.waiting}；读取/格式异常 ${result.errors}").apply()
                    if(result.waiting>0 && result.finished)retrySoon=true
                }catch(e:kotlinx.coroutines.CancellationException){throw e}
                catch(_:Exception){scanned[platform]=false;preferences(app).edit().putString("system_scan_$platform","目录暂不可读或扫描未完成；输入法录音最多等待10分钟后独立上传").apply()}
            }
            CallRecordingUploader(box,transport,allowed,{ServerConfig.baseUrl},
                systemSourceExists=documents::exists,onSaved=importer::markSaved,
                ready={task->(task.source==null || task.metadata.recording_ended_at>=System.currentTimeMillis()-7*86_400_000L) &&
                    systemRecordingUploadReady(task,documents.tree(task.metadata.platform)!=null,
                    scanned[task.metadata.platform]==true,System.currentTimeMillis())}).runOnce()
            if(box.tasks().any{it.source==null && it.cleanupStatus!="deleted" &&
                !systemRecordingUploadReady(it,documents.tree(it.metadata.platform)!=null,scanned[it.metadata.platform]==true,System.currentTimeMillis())})retrySoon=true
        }
        return retrySoon
    }
}
class CallRecordingRecoveryReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        if(intent.action in setOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED))CallRecordingRuntime.restore(context)
    }
}
