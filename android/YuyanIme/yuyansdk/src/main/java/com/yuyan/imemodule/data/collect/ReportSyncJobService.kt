package com.yuyan.imemodule.data.collect

import android.app.job.JobInfo
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
            if (scheduler.allPendingJobs.any { it.id == JOB_ID }) return
            scheduler.schedule(JobInfo.Builder(JOB_ID, ComponentName(context, ReportSyncJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(true)
                .build())
        }
        fun cancel(context: Context) { (context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler).cancel(JOB_ID) }
    }
}
