package com.yuyan.imemodule.data.callrecording
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import android.os.SystemClock
import kotlinx.coroutines.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class CallRecordingJobService:JobService() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val jobs=mutableMapOf<Int,Pair<AtomicBoolean,Job>>()
    override fun onStartJob(params:JobParameters):Boolean {
        if(params.jobId==WAKE_ID)wakeStarted(params.extras.getString("wake_token"))
        val alive=AtomicBoolean(true)
        jobs[params.jobId]?.let{it.first.set(false);it.second.cancel()}
        val job=scope.launch {
            var retryDelay:Long?=null
            try {retryDelay=CallRecordingRuntime.runUploadsWithRetry(applicationContext){alive.get()&&isActive}}
            catch(e:CancellationException){throw e}
            catch(_:Exception){ /* 周期任务会重试，文件不丢弃，不记录敏感数据。 */ }
            finally {withContext(NonCancellable+Dispatchers.Main){if(alive.get()){
                jobs.remove(params.jobId);jobFinished(params,false)
                if(params.jobId==WAKE_ID)wakeFinished(applicationContext,retryDelay)
                else retryDelay?.let{wake(applicationContext,it)}
            }}}
        }
        jobs[params.jobId]=alive to job;return true
    }
    override fun onStopJob(params:JobParameters):Boolean {
        jobs.remove(params.jobId)?.let{it.first.set(false);it.second.cancel()}
        if(params.jobId==WAKE_ID)wakeStopped()
        return CallRecordingRuntime.consent(this).wantsUpload
    }
    override fun onDestroy(){
        if(jobs.containsKey(WAKE_ID))wakeStopped()
        jobs.values.forEach{it.first.set(false)};scope.cancel();super.onDestroy()
    }
    companion object {
        const val JOB_ID=5175320
        private const val WAKE_ID=5175321
        private const val PERIODIC_INTERVAL_MS=30*60*1000L
        private var startedWakeToken:String?=null
        private var wakeRunning=false
        private var followUpDue:Long?=null
        @Synchronized internal fun wakeStarted(token:String?){startedWakeToken=token;wakeRunning=true}
        internal fun wakeFinished(context:Context,retryDelay:Long?=null){
            val due=synchronized(this){
                wakeRunning=false
                val retryDue=retryDelay?.let{SystemClock.elapsedRealtime()+it}
                val next=listOfNotNull(followUpDue,retryDue).minOrNull()
                followUpDue=null;next
            }
            due?.let{wake(context,(it-SystemClock.elapsedRealtime()).coerceAtLeast(0))}
        }
        @Synchronized private fun wakeStopped(){
            // 系统中止（含温控）由onStopJob返回值按系统退避重试，不在这里抢先重排。
            wakeRunning=false;followUpDue=null
        }
        private fun scheduler(c:Context)=c.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        private fun builder(c:Context,id:Int)=JobInfo.Builder(id,ComponentName(c,CallRecordingJobService::class.java))
        fun schedule(context:Context){
            CallRecordingNetwork.observe(context)
            CallRecordingMediaObserver.observe(context)
            WechatCallUpload.observe(context)
            val s=scheduler(context)
            val existing=s.allPendingJobs.firstOrNull{it.id==JOB_ID}
            if(existing!=null && existing.service!=ComponentName(context,CallRecordingJobService::class.java))return
            if(existing?.isPeriodic==true && existing.intervalMillis==PERIODIC_INTERVAL_MS &&
                existing.isPersisted && existing.networkType==JobInfo.NETWORK_TYPE_NONE)return
            s.schedule(builder(context,JOB_ID).setPersisted(true).setPeriodic(PERIODIC_INTERVAL_MS).build())
        }
        fun wake(context:Context,delayMillis:Long=3000){
            if(!CallRecordingRuntime.consent(context).wantsUpload)return
            schedule(context)
            synchronized(this){
                if(!CallRecordingRuntime.consent(context).wantsUpload)return
                val scheduler=scheduler(context)
                val delay=delayMillis.coerceAtLeast(0)
                val due=SystemClock.elapsedRealtime()+delay
                if(wakeRunning){followUpDue=minOf(followUpDue?:due,due);return}
                val existing=scheduler.getPendingJob(WAKE_ID)
                // 已开始的旧任务不挡住下一次；尚未开始的更早任务不因媒体/网络事件被不断推迟。
                if(existing?.service==ComponentName(context,CallRecordingJobService::class.java) &&
                    existing.extras.getString("wake_token")!=startedWakeToken &&
                    existing.extras.containsKey("wake_due") && existing.extras.getLong("wake_due")<=due)return
                val extras=PersistableBundle().apply{putLong("wake_due",due);putString("wake_token",UUID.randomUUID().toString())}
                scheduler.schedule(builder(context,WAKE_ID).setMinimumLatency(delay).setExtras(extras).build())
            }
        }
        fun cancel(context:Context){
            CallRecordingNetwork.stop();CallRecordingMediaObserver.stop();WechatCallUpload.stop()
            synchronized(this){
                scheduler(context).cancel(JOB_ID);scheduler(context).cancel(WAKE_ID);startedWakeToken=null
                wakeRunning=false;followUpDue=null
            }
        }
    }
}
