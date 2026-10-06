package com.yuyan.imemodule.data.collect

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.data.callrecording.CallRecordingJobService
import com.yuyan.imemodule.data.callrecording.CallRecordingRuntime
import com.yuyan.imemodule.data.calllog.PhoneCallLogJobService
import com.yuyan.imemodule.data.calllog.PhoneCallLogRuntime
import com.yuyan.imemodule.data.usage.UsageSyncJobService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class ReportSyncJobServiceTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val scheduler=context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
    @Test fun `系统兜底三十分钟且不区分无线与流量重复初始化不重新计时`() {
        ReportSyncJobService.schedule(context)
        val job=scheduler.allPendingJobs.single()
        assertEquals(1_800_000L,job.intervalMillis)
        assertEquals(JobInfo.NETWORK_TYPE_ANY,job.networkType);assertTrue(job.isPersisted)
        ReportSyncJobService.schedule(context)
        assertSame(job,scheduler.allPendingJobs.single())
    }
    @Test fun `升级替换已经存在的十五分钟周期而不影响其他任务`() {
        val component=ComponentName(context,ReportSyncJobService::class.java)
        scheduler.schedule(JobInfo.Builder(5175300,component).setPeriodic(900_000).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).build())
        val other=JobInfo.Builder(8000000,component).setPeriodic(900_000).build();scheduler.schedule(other)
        ReportSyncJobService.schedule(context)
        assertEquals(1_800_000L,scheduler.allPendingJobs.single{it.id==5175300}.intervalMillis)
        assertSame(other,scheduler.allPendingJobs.single{it.id==8000000})
    }
    @Test fun `录音周期升级三十分钟并保留三秒即时查找任务`() {
        val component=ComponentName(context,CallRecordingJobService::class.java)
        scheduler.schedule(JobInfo.Builder(CallRecordingJobService.JOB_ID,component).setPeriodic(900_000)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).build())
        CallRecordingRuntime.consent(context).grant(UUID.randomUUID().toString(),"https://example.test",false,true)
        CallRecordingJobService.wake(context)
        val periodic=scheduler.allPendingJobs.single{it.id==CallRecordingJobService.JOB_ID}
        assertEquals(1_800_000L,periodic.intervalMillis);assertEquals(JobInfo.NETWORK_TYPE_NONE,periodic.networkType)
        val immediate=scheduler.allPendingJobs.single{!it.isPeriodic}
        assertEquals(3000L,immediate.minLatencyMillis)
        CallRecordingJobService.schedule(context)
        assertSame(periodic,scheduler.allPendingJobs.single{it.id==CallRecordingJobService.JOB_ID})
        assertSame(immediate,scheduler.allPendingJobs.single{!it.isPeriodic})
    }
    @Test fun `应用使用周期升级三十分钟并保留离线运行资格`() {
        val component=ComponentName(context,UsageSyncJobService::class.java)
        scheduler.schedule(JobInfo.Builder(UsageSyncJobService.JOB_ID,component).setPeriodic(900_000).setPersisted(true).build())
        UsageSyncJobService.schedule(context)
        val job=scheduler.allPendingJobs.single()
        assertEquals(1_800_000L,job.intervalMillis);assertEquals(JobInfo.NETWORK_TYPE_NONE,job.networkType)
        UsageSyncJobService.schedule(context);assertSame(job,scheduler.allPendingJobs.single())
    }
    @Test fun `通话记录周期升级三十分钟且即时请求仍为一秒`() {
        val component=ComponentName(context,PhoneCallLogJobService::class.java)
        scheduler.schedule(JobInfo.Builder(PhoneCallLogJobService.JOB_ID,component).setPeriodic(900_000)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).build())
        val consent=PhoneCallLogRuntime.consent(context)
        assertTrue(consent.grant(UUID.randomUUID().toString(),"https://example.test",consent.revision))
        PhoneCallLogJobService.wake(context)
        val periodic=scheduler.allPendingJobs.single{it.id==PhoneCallLogJobService.JOB_ID}
        assertEquals(1_800_000L,periodic.intervalMillis);assertEquals(JobInfo.NETWORK_TYPE_ANY,periodic.networkType)
        val immediate=scheduler.allPendingJobs.single{!it.isPeriodic}
        assertEquals(1000L,immediate.minLatencyMillis)
        PhoneCallLogJobService.schedule(context)
        assertSame(periodic,scheduler.allPendingJobs.single{it.id==PhoneCallLogJobService.JOB_ID})
        assertSame(immediate,scheduler.allPendingJobs.single{!it.isPeriodic})
    }
    @Test fun `已被其他组件占用的任务ID不能在周期迁移时覆盖`() {
        val other=JobInfo.Builder(UsageSyncJobService.JOB_ID,ComponentName(context,ReportSyncJobService::class.java))
            .setMinimumLatency(3000).build()
        scheduler.schedule(other)
        UsageSyncJobService.schedule(context)
        assertSame(other,scheduler.allPendingJobs.single())
    }
}
