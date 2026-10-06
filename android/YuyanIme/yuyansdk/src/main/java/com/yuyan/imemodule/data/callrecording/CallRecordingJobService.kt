package com.yuyan.imemodule.data.callrecording
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

class CallRecordingJobService:JobService() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val jobs=mutableMapOf<Int,Pair<AtomicBoolean,Job>>()
    override fun onStartJob(params:JobParameters):Boolean {
        val alive=AtomicBoolean(true)
        jobs[params.jobId]?.let{it.first.set(false);it.second.cancel()}
        val job=scope.launch {
            var retrySoon=false
            try {retrySoon=CallRecordingRuntime.runUploads(applicationContext){alive.get()&&isActive}}
            catch(e:CancellationException){throw e}
            catch(_:Exception){ /* 周期任务会重试，文件不丢弃，不记录敏感数据。 */ }
            finally {withContext(NonCancellable+Dispatchers.Main){if(alive.get()){
                jobs.remove(params.jobId);jobFinished(params,false)
                if(retrySoon)wake(applicationContext,35_000)
            }}}
        }
        jobs[params.jobId]=alive to job;return true
    }
    override fun onStopJob(params:JobParameters):Boolean {
        jobs.remove(params.jobId)?.let{it.first.set(false);it.second.cancel()}
        return CallRecordingRuntime.consent(this).wantsUpload
    }
    override fun onDestroy(){jobs.values.forEach{it.first.set(false)};scope.cancel();super.onDestroy()}
    companion object {
        const val JOB_ID=5175320
        private const val WAKE_ID=5175321
        private const val PERIODIC_INTERVAL_MS=30*60*1000L
        private fun scheduler(c:Context)=c.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        private fun builder(c:Context,id:Int)=JobInfo.Builder(id,ComponentName(c,CallRecordingJobService::class.java))
        fun schedule(context:Context){
            CallRecordingWifi.observe(context)
            val s=scheduler(context)
            val existing=s.allPendingJobs.firstOrNull{it.id==JOB_ID}
            if(existing!=null && existing.service!=ComponentName(context,CallRecordingJobService::class.java))return
            if(existing?.isPeriodic==true && existing.intervalMillis==PERIODIC_INTERVAL_MS &&
                existing.isPersisted && existing.networkType==JobInfo.NETWORK_TYPE_NONE)return
            s.schedule(builder(context,JOB_ID).setPersisted(true).setPeriodic(PERIODIC_INTERVAL_MS).build())
        }
        fun wake(context:Context,delayMillis:Long=3000){if(CallRecordingRuntime.consent(context).wantsUpload){schedule(context);scheduler(context).schedule(builder(context,WAKE_ID).setMinimumLatency(delayMillis).build())}}
        fun cancel(context:Context){CallRecordingWifi.stop();scheduler(context).cancel(JOB_ID);scheduler(context).cancel(WAKE_ID)}
    }
}
