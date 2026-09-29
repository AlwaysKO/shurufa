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
        fun schedule(context: Context) {
            val scheduler=context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            if(scheduler.allPendingJobs.any { it.id==JOB_ID }) return
            scheduler.schedule(JobInfo.Builder(JOB_ID,ComponentName(context,UsageSyncJobService::class.java))
                .setPeriodic(15*60*1000L).setPersisted(true).build())
        }
        fun cancel(context: Context) { (context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler).cancel(JOB_ID) }
    }
}
