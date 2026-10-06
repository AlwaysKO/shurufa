package com.yuyan.imemodule.data.calllog

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

class PhoneCallLogJobService:JobService() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val jobs=mutableMapOf<Int,Pair<AtomicBoolean,Job>>()
    override fun onStartJob(params:JobParameters):Boolean {
        val alive=AtomicBoolean(true)
        jobs.remove(params.jobId)?.let{it.first.set(false);it.second.cancel()}
        val job=scope.launch {
            try{PhoneCallLogRuntime.run(applicationContext){alive.get()&&isActive}}
            finally{withContext(NonCancellable+Dispatchers.Main){if(alive.get()){jobs.remove(params.jobId);jobFinished(params,false)}}}
        }
        jobs[params.jobId]=alive to job;return true
    }
    override fun onStopJob(params:JobParameters):Boolean {
        jobs.remove(params.jobId)?.let{it.first.set(false);it.second.cancel()}
        return PhoneCallLogRuntime.consent(this).enabled
    }
    override fun onDestroy(){jobs.values.forEach{it.first.set(false)};scope.cancel();super.onDestroy()}
    companion object {
        const val JOB_ID=5175322
        private const val WAKE_ID=5175323
        private const val PERIODIC_INTERVAL_MS=30*60*1000L
        private fun scheduler(c:Context)=c.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        private fun builder(c:Context,id:Int)=JobInfo.Builder(id,ComponentName(c,PhoneCallLogJobService::class.java)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
        fun schedule(c:Context){
            val s=scheduler(c)
            val existing=s.allPendingJobs.firstOrNull{it.id==JOB_ID}
            if(existing!=null && existing.service!=ComponentName(c,PhoneCallLogJobService::class.java))return
            if(existing?.isPeriodic==true && existing.intervalMillis==PERIODIC_INTERVAL_MS &&
                existing.isPersisted && existing.networkType==JobInfo.NETWORK_TYPE_ANY)return
            s.schedule(builder(c,JOB_ID).setPersisted(true).setPeriodic(PERIODIC_INTERVAL_MS).build())
        }
        fun wake(c:Context){if(PhoneCallLogRuntime.consent(c).enabled){schedule(c);scheduler(c).schedule(builder(c,WAKE_ID).setMinimumLatency(1000).build())}}
        fun cancel(c:Context){scheduler(c).cancel(JOB_ID);scheduler(c).cancel(WAKE_ID)}
    }
}
