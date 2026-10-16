package com.yuyan.imemodule.data.collect

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReportSyncDemandSchedulingTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val scheduler = app.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler

    @Test fun initializationDoesNotCreateAnEmptyPeriodicUpload() {
        ReportSyncJobService.schedule(app)
        assertTrue(scheduler.allPendingJobs.isEmpty())
    }

    @Test fun upgradeRetiresLegacyPeriodicUploadWithoutTouchingOtherJobs() {
        val component = ComponentName(app, ReportSyncJobService::class.java)
        scheduler.schedule(JobInfo.Builder(5175300, component).setPeriodic(1_800_000)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).build())
        val other = JobInfo.Builder(8000000, component).setMinimumLatency(300_000).build()
        scheduler.schedule(other)
        ReportSyncJobService.schedule(app)
        assertNull(scheduler.getPendingJob(5175300))
        assertSame(other, scheduler.getPendingJob(other.id))
    }

    @Test fun legacyRetirementDoesNotCancelAnotherComponentsJob() {
        val other = JobInfo.Builder(5175300, ComponentName(app, UploadRecoveryJobService::class.java))
            .setMinimumLatency(300_000).build()
        scheduler.schedule(other)
        ReportSyncJobService.schedule(app)
        assertSame(other, scheduler.getPendingJob(other.id))
    }

    @Test fun pendingRecoverySurvivesRebootWithoutRecurringPolling() {
        PreferenceManager.getDefaultSharedPreferences(app).edit().putBoolean(CollectionConsent.KEY, true).commit()
        try {
            UploadRecovery.cancel(app)
            UploadRecovery.wake(app, 300_000)
            val job = scheduler.allPendingJobs.single()
            assertFalse(job.isPeriodic)
            assertTrue(job.isPersisted)
            assertEquals(300_000L, job.minLatencyMillis)
            assertEquals(JobInfo.NETWORK_TYPE_ANY, job.networkType)
        } finally { UploadRecovery.cancel(app) }
    }
}
