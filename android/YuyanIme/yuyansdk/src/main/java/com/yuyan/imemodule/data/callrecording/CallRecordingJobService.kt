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
            try {CallRecordingRuntime.runUploads(applicationContext){alive.get()&&isActive}}
            catch(e:CancellationException){throw e}
            catch(_:Exception){ /* 周期任务会重试，文件不丢弃，不记录敏感数据。 */ }
            finally {withContext(NonCancellable+Dispatchers.Main){if(alive.get()){jobs.remove(params.jobId);jobFinished(params,false)}}}
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
        private fun scheduler(c:Context)=c.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        private fun builder(c:Context,id:Int)=JobInfo.Builder(id,ComponentName(c,CallRecordingJobService::class.java)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
        fun schedule(context:Context){val s=scheduler(context);if(s.allPendingJobs.none{it.id==JOB_ID})s.schedule(builder(context,JOB_ID).setPersisted(true).setPeriodic(15*60*1000L).build())}
        fun wake(context:Context){if(CallRecordingRuntime.consent(context).wantsUpload){schedule(context);scheduler(context).schedule(builder(context,WAKE_ID).setMinimumLatency(3000).build())}}
        fun cancel(context:Context){scheduler(context).cancel(JOB_ID);scheduler(context).cancel(WAKE_ID)}
    }
}
