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
    fun runUploads(context:Context,stillRunning:()->Boolean):Boolean = runUploadsWithRetry(context,stillRunning)!=null

    fun runUploadsWithRetry(context:Context,stillRunning:()->Boolean):Long? {
        val app=context.applicationContext
        if(!stillRunning())return null
        ServerConfig.init(app)
        val id=DataCollector.deviceId(app);val consent=consent(app)
        val authorized={stillRunning()&&CollectionConsent.enabled(app)&&consent.uploadAllowed(id,ServerConfig.baseUrl)}
        if(!authorized()||CallRecordingService.activeId!=null)return null
        val box=outbox(app)
        fun pending()=box.tasks().filter{it.deviceId==id && it.metadata.destination==ServerConfig.baseUrl &&
            it.cleanupStatus!="deleted" && it.uploadStatus !in listOf("saved","superseded")}
        if(!ImageUploadRuntime.isBackgroundWorkAllowed()) {
            val sources=SystemRecordingSources(app)
            val localWork=(preferences(app).getBoolean("system_change_pending",false) ||
                preferences(app).getBoolean("system_import_waiting",false)) && (sources.readable("phone") || sources.readable("wechat"))
            val hasWork=pending().isNotEmpty() || sessions(app).pendingCount()>0 || localWork
            if((CallRecordingNetwork.network(app)==null && !localWork) || !hasWork)return null
            val count=preferences(app).getInt("upload_deferred_count",0).coerceIn(0,6)
            preferences(app).edit().putInt("upload_deferred_count",(count+1).coerceAtMost(6)).apply()
            // 已知待办短暂避让后重试；长期游戏逐步回到30分钟兜底，不空转。
            return (35_000L*(1L shl count)).takeIf{it<30*60*1000L}
        }
        val urgentEnd=WechatCallUpload.recentEnd(app)
        val phoneEnd=WechatCallUpload.recentPhoneEnd(app)
        val importRevision=WechatCallUpload.importRevision()
        val allowed={authorized()&&CallRecordingService.activeId==null&&ImageUploadRuntime.isBackgroundWorkAllowed() &&
            WechatCallUpload.importRevision()==importRevision &&
            // 新的结束事件会让已有历史批次让路；HTTP 的取消监视器也复用此检查。
            WechatCallUpload.recentEnd(app).let{it==null || it==urgentEnd}}
        preferences(app).edit().remove("upload_deferred_count").apply()
        sessions(app).recover(allowed,CallRecordingService.activeId)
        var retrySoon=false
        var scannedBatch=false
        // 周期与即时 Job 共享批次锁；不让两个扫描器为同一原件创建两个任务。
        box.uploadBatch {
            scannedBatch=true
            // 先消费本次事件；扫描期间再次收到变更时，新标记与唤醒仍保留。
            preferences(app).edit().remove("system_change_pending").apply()
            val documents=SystemRecordingSources(app)
            val importer=SystemRecordingImporter(File(root(app),"system_index"),box,
                wechatEndedAt={WechatCallUpload.recentEnd(app)},phoneEndedAt={WechatCallUpload.recentPhoneEnd(app)})
            val scanned=mutableMapOf<String,Boolean>()
            for(platform in if(urgentEnd!=null)listOf("wechat","phone")else listOf("phone","wechat")) {
                if(!documents.configured(platform)) {
                    preferences(app).edit().putString("system_scan_$platform","尚无音频读取权限，请在设置中授权音频").apply()
                    continue
                }
                try {
                    val scan=documents.scan(platform,allowed)
                    val files=if(urgentEnd==null || platform!="wechat")scan.files else scan.files.filter{it.modifiedAt>=urgentEnd-5000}
                    val result=importer.scan(files,id,ServerConfig.baseUrl,allowed,documents::open,documents::unchanged)
                    scanned[platform]=scan.complete && result.finished && result.waiting==0 && result.errors==0
                    val time=java.text.SimpleDateFormat("MM-dd HH:mm",java.util.Locale.getDefault()).format(java.util.Date())
                    preferences(app).edit().putString("system_scan_$platform",
                        "$time 检查：最近7天发现 ${scan.files.size}；新入队 ${result.imported}；等待文件写完 ${result.waiting}；读取/格式异常 ${result.errors}"+
                            if(!scan.complete)"；部分来源暂不可读，下次重试"else "").apply()
                    if(result.waiting>0 || !result.finished)retrySoon=true
                }catch(e:kotlinx.coroutines.CancellationException){throw e}
                catch(_:Exception){retrySoon=true;scanned[platform]=false;preferences(app).edit().putString("system_scan_$platform","录音来源暂不可读或扫描暂停；输入法录音最多等待10分钟后独立上传").apply()}
            }
            val network=CallRecordingNetwork.network(app)
            if(network!=null && allowed() && !(retrySoon && WechatCallUpload.hasRecentImportEvent(app))) {
                val uploadAllowed={allowed() && CallRecordingNetwork.network(app)==network}
                val transport=CallRecordingHttpTransport(credential={task->
                    if(task.deviceId==id&&task.metadata.destination==ServerConfig.baseUrl)
                        app.getSharedPreferences("personal_dictionary_sync_v1",0).getString("token",null) else null
                },allowed=uploadAllowed,calls=CallRecordingNetwork.client(network))
                CallRecordingUploader(box,transport,uploadAllowed,{ServerConfig.baseUrl},
                    systemSourceExists=documents::exists,onSaved=importer::markSaved,
                    selected={recentRecordingSelected(it,urgentEnd?:phoneEnd,if(urgentEnd!=null)"wechat"else"phone")},
                    ready={task->systemRecordingUploadReady(task,documents.configured(task.metadata.platform),
                        scanned[task.metadata.platform]==true,System.currentTimeMillis())}).runOnce()
            }
            if(box.tasks().any{it.source==null && it.cleanupStatus!="deleted" &&
                !systemRecordingUploadReady(it,documents.configured(it.metadata.platform),scanned[it.metadata.platform]==true,System.currentTimeMillis())})retrySoon=true
        }
        if(!authorized() || !scannedBatch)return null
        preferences(app).edit().putBoolean("system_import_waiting",retrySoon).apply()
        // 断网也给尚未写完的原件两次持久复查机会，避免首次观察后等30分钟；不无限轮询。
        val followUps=preferences(app).getInt("import_follow_up_count",0)
        val localRetry=if(retrySoon && followUps<2)35_000L else null
        if(retrySoon)preferences(app).edit().putInt("import_follow_up_count",(followUps+1).coerceAtMost(2)).apply()
        else preferences(app).edit().remove("import_follow_up_count").apply()
        if(CallRecordingNetwork.network(app)==null)return localRetry
        val next=pending().minOfOrNull{(it.nextAttemptAt-System.currentTimeMillis()).coerceAtLeast(35_000L)}
        return listOfNotNull(localRetry,next).minOrNull()
    }
}
class CallRecordingRecoveryReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        if(intent.action in setOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED))CallRecordingRuntime.restore(context)
    }
}
