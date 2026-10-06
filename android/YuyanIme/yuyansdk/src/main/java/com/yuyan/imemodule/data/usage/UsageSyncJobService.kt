package com.yuyan.imemodule.data.usage

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.*

/** No network constraint: preserve system history offline, then retry independent targets. */
class UsageSyncJobService: JobService() {
    private var running: Job?=null
    override fun onStartJob(params: JobParameters): Boolean {
        if(!AppUsageTracker.enabled(this)) return false
        running=CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {
            try { AppUsageTracker.sync(applicationContext); jobFinished(params,false) }
            catch (_: CancellationException) { }
            catch (_: Exception) { jobFinished(params,true) }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { running?.cancel(); running=null; return AppUsageTracker.enabled(this) }
    override fun onDestroy() { running?.cancel(); super.onDestroy() }
    companion object {
        const val JOB_ID=5175302
        private const val PERIODIC_INTERVAL_MS=30*60*1000L
        fun schedule(context: Context) {
            val scheduler=context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val component=ComponentName(context,UsageSyncJobService::class.java)
            val existing=scheduler.allPendingJobs.firstOrNull{it.id==JOB_ID}
            // 迁移旧周期时不能覆盖已被其他组件占用的任务 ID。
            if(existing!=null && existing.service!=component)return
            if(existing?.isPeriodic==true && existing.intervalMillis==PERIODIC_INTERVAL_MS &&
                existing.isPersisted && existing.networkType==JobInfo.NETWORK_TYPE_NONE)return
            scheduler.schedule(JobInfo.Builder(JOB_ID,component)
                .setPeriodic(PERIODIC_INTERVAL_MS).setPersisted(true).build())
        }
        fun cancel(context: Context) {
            val scheduler=context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val component=ComponentName(context,UsageSyncJobService::class.java)
            if(scheduler.allPendingJobs.any { it.id==JOB_ID && it.service==component }) scheduler.cancel(JOB_ID)
        }
    }
}
