package com.yuyan.imemodule.data.callrecording

import android.content.Context
import android.media.AudioManager
import com.yuyan.imemodule.data.collect.CollectionConsent
import com.yuyan.imemodule.data.collect.ImageUploadRuntime
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 即时请求合并，执行期间的新事件留到下一轮；取消后旧定时回调不能复活。 */
internal class WechatCallUploadDispatch(
    private val now:()->Long,
    private val schedule:(Long,()->Unit)->Unit,
    private val allowed:()->Boolean,
    private val upload:()->Long?,
    private val followUp:()->Long?,
    private val retry:(Long)->Unit,
) {
    private var ticket=0L
    private var epoch=0L
    private var due:Long?=null
    private var running=false
    private var again=false
    @Synchronized fun request(delay:Long=0) {
        if(running){again=true;return}
        val next=now()+delay.coerceAtLeast(0)
        if(due?.let{it<=next}==true)return
        due=next
        val token=++ticket;val generation=epoch
        schedule(delay.coerceAtLeast(0)){execute(token,generation)}
    }
    private fun execute(token:Long,generation:Long) {
        synchronized(this){
            if(ticket!=token || epoch!=generation)return
            due=null;running=true
        }
        try {
            if(allowed())upload()?.let(retry)
        }catch(_:Exception){retry(35_000)}
        finally {
            synchronized(this){
                running=false
                if(again){again=false;request()}
                else if(epoch==generation && allowed())followUp()?.let{request(it)}
            }
        }
    }
    @Synchronized fun cancel(){epoch++;ticket++;due=null;again=false}
}

internal fun wechatCallUploadFollowUp(endedAt:Long?,now:Long):Long? {
    val elapsed=endedAt?.let{now-it}?:return null
    return when(elapsed){in 0..4_999->2000L;in 5_000..14_999->5000L;in 15_000..59_999->15000L;else->null}
}

/** 上传授权可独立订阅通话状态；绝不因状态通知启动麦克风。 */
internal object WechatCallUpload {
    private val worker=Executors.newSingleThreadScheduledExecutor { task->
        Thread(task,"wechat-call-upload").apply{isDaemon=true;priority=Thread.MIN_PRIORITY}
    }
    private var connection:AutoCloseable?=null
    private var dispatch:WechatCallUploadDispatch?=null
    @Volatile private var active=false
    @Volatile private var endedAt:Long?=null
    private val changes=java.util.concurrent.atomic.AtomicLong()
    fun importRevision()=changes.get()
    fun hasRecentImportEvent(context:Context)=wechatCallUploadFollowUp(
        CallRecordingRuntime.preferences(context).getLong("import_event_at",0).takeIf{it>0},System.currentTimeMillis())!=null

    @Synchronized fun observe(context:Context) {
        val app=context.applicationContext
        if(!CallRecordingRuntime.consent(app).wantsUpload || !CollectionConsent.enabled(app)){stop();return}
        if(connection!=null)return
        dispatch=WechatCallUploadDispatch(
            now=android.os.SystemClock::elapsedRealtime,
            schedule={delay,block->worker.schedule({block()},delay,TimeUnit.MILLISECONDS);Unit},
            allowed={CallRecordingRuntime.consent(app).wantsUpload && CollectionConsent.enabled(app) &&
                ImageUploadRuntime.isBackgroundWorkAllowed()},
            upload={CallRecordingRuntime.runUploadsWithRetry(app){!active}},
            followUp={wechatCallUploadFollowUp(CallRecordingRuntime.preferences(app).getLong("import_event_at",0).takeIf{it>0},System.currentTimeMillis())},
            retry={CallRecordingJobService.wake(app,it)})
        connection=WechatCallSignals.connect { isActive,_->
            active=isActive
            if(isActive){endedAt=null;synchronized(this){dispatch?.cancel()}}
            else finished(app)
        }
    }
    fun finished(context:Context) {
        if(!CallRecordingRuntime.consent(context).wantsUpload || !CollectionConsent.enabled(context))return
        active=false;endedAt=System.currentTimeMillis()
        requestImport(context)
    }
    fun phoneFinished(context:Context) {
        if(!CallRecordingRuntime.consent(context).wantsUpload || !CollectionConsent.enabled(context))return
        CallRecordingRuntime.preferences(context).edit().putLong("phone_ended_at",System.currentTimeMillis()).apply()
        requestImport(context)
    }
    private fun requestImport(context:Context) {
        changes.incrementAndGet()
        CallRecordingRuntime.preferences(context).edit().putLong("import_event_at",System.currentTimeMillis())
            .putBoolean("system_change_pending",true).remove("import_follow_up_count").apply()
        // 即使进程随后退出，也已保留系统兜底；前台进程不等待 JobScheduler 才尝试。
        CallRecordingJobService.wake(context,0)
        synchronized(this){dispatch?.request()}
    }
    fun mediaChanged(context:Context) {
        if(CallRecordingRuntime.consent(context).wantsUpload && CollectionConsent.enabled(context))requestImport(context)
    }
    fun recentPhoneEnd(context:Context):Long? {
        val end=CallRecordingRuntime.preferences(context).getLong("phone_ended_at",0)
        val mode=(context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.mode
        return end.takeIf{it>0 && System.currentTimeMillis()-it in 0..60_000 && mode==AudioManager.MODE_NORMAL}
    }
    fun recentEnd(context:Context):Long? {
        val end=endedAt
        if(active || wechatCallUploadFollowUp(end,System.currentTimeMillis())==null)return null
        val mode=(context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.mode
        return end.takeIf{mode==AudioManager.MODE_NORMAL}
    }
    @Synchronized fun stop() {
        connection?.close();connection=null
        dispatch?.cancel();dispatch=null
        active=false;endedAt=null
    }
}

internal fun recentRecordingSelected(task:CallTask,endedAt:Long?,platform:String="wechat"):Boolean = endedAt==null ||
    (task.metadata.platform==platform && (task.source?.modifiedAt ?: task.metadata.recording_ended_at)>=endedAt-5000)
