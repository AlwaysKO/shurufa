package com.yuyan.imemodule.data.collect

import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.*

/** System-managed retry after process death; Android may defer it under Doze. */
class ReportSyncJobService : JobService() {
    private var job: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        if (!CollectionConsent.enabled(this)) return false
        job = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                DataCollector.init(applicationContext)
                DataCollector.flushNow()
                jobFinished(params, false)
            } catch (_: CancellationException) {
                // onStopJob owns retry; never call jobFinished after Android stopped this job.
            } catch (_: Exception) { jobFinished(params, true) }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        job?.cancel(); job = null
        DataCollector.cancelTransfers()
        return CollectionConsent.enabled(this)
    }
    companion object {
        private const val JOB_ID = 5175300
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val component = ComponentName(context, ReportSyncJobService::class.java)
            val existing = scheduler.allPendingJobs.firstOrNull { it.id == JOB_ID }
            if (existing != null && existing.service != component) return
            // 新待传由持久一次性恢复任务负责；升级时退休空队列也会唤醒的旧周期。
            if (existing != null) scheduler.cancel(JOB_ID)
        }
        fun cancel(context: Context) { (context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler).cancel(JOB_ID) }
    }
}
