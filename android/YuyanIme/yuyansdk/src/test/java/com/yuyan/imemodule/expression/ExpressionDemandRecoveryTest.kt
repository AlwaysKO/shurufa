package com.yuyan.imemodule.expression

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.data.collect.BackgroundRefreshRuntime
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ExpressionDemandRecoveryTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val scheduler = app.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler

    @Before fun networkReady() {
        val manager = app.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val shadow = org.robolectric.Shadows.shadowOf(manager)
        shadow.setActiveNetworkInfo(org.robolectric.shadows.ShadowNetworkInfo.newInstance(
            android.net.NetworkInfo.DetailedState.CONNECTED, android.net.ConnectivityManager.TYPE_WIFI, 0, true, true))
        val caps = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        org.robolectric.Shadows.shadowOf(caps).apply {
            addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
            addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        shadow.setNetworkCapabilities(manager.activeNetwork, caps)
    }

    @Test fun startupAndNetworkRecoveryKeepServerUpdateFallback() {
        ExpressionSyncJobService.startRecovery(app)
        BackgroundRefreshRuntime.networkChanged(app)
        val periodic = scheduler.getPendingJob(5175301)
        assertNotNull(periodic)
        assertTrue(periodic!!.isPeriodic)
        assertEquals(1_800_000L, periodic.intervalMillis)
        assertTrue(periodic.isPersisted)
    }

    @Test fun explicitActivityRequestStillChecksResources() {
        BackgroundRefreshRuntime.request(app, userInitiated = true)
        val check = scheduler.allPendingJobs.single { it.id == 5175303 }
        assertFalse(check.isPeriodic)
        assertTrue(check.extras.getBoolean("user_initiated"))
    }

    @Test fun periodicServerCheckAndPendingDownloadBothSurviveRecovery() {
        val component = ComponentName(app, ExpressionSyncJobService::class.java)
        scheduler.schedule(JobInfo.Builder(5175301, component).setPeriodic(1_800_000)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).build())
        ExpressionSyncJobService.scheduleDownload(app, "scope", "v2")
        val download = scheduler.getPendingJob(5175332)
        ExpressionSyncJobService.startRecovery(app)
        assertNotNull(scheduler.getPendingJob(5175301))
        assertSame(download, scheduler.getPendingJob(5175332))
        assertTrue(download!!.isPersisted)
    }
}
