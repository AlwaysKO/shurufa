package com.yuyan.imemodule.expression

import android.app.job.JobScheduler
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Robolectric
import org.robolectric.util.ReflectionHelpers
import kotlinx.coroutines.*

@RunWith(RobolectricTestRunner::class)
class ExpressionSyncJobServiceTest {
    @Test @Config(sdk = [30]) fun `半小时版本检查允许移动网络且不要求空闲`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        scheduler.cancelAll()
        ExpressionSyncJobService.schedule(context)
        val job = scheduler.allPendingJobs.single()
        assertEquals(30 * 60 * 1000L, job.intervalMillis)
        assertFalse(job.isRequireDeviceIdle)
        assertTrue(job.isPersisted)
        assertFalse(job.requiredNetwork!!.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
        ExpressionSyncJobService.schedule(context)
        assertEquals(1, scheduler.allPendingJobs.size)
        assertSame("重复初始化不重新计时", job, scheduler.allPendingJobs.single())
    }

    @Test @Config(sdk = [28]) fun `升级时替换已经持久化的六小时任务`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        scheduler.cancelAll()
        scheduler.schedule(JobInfo.Builder(5175301, ComponentName(context, ExpressionSyncJobService::class.java))
            .setPeriodic(6 * 60 * 60 * 1000L).setRequiresDeviceIdle(true)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED).setPersisted(true).build())
        ExpressionSyncJobService.schedule(context)
        assertEquals(30 * 60 * 1000L, scheduler.allPendingJobs.single().intervalMillis)
        assertFalse(scheduler.allPendingJobs.single().isRequireDeviceIdle)
    }

    @Test @Config(sdk = [30]) fun `下载单独等待WiFi且不带半小时或空闲门槛`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        scheduler.cancelAll()
        ExpressionSyncJobService.schedule(context)
        ExpressionSyncJobService.scheduleDownload(context, "scope", "v2")
        val download = scheduler.allPendingJobs.single { !it.isPeriodic }
        assertTrue(download.requiredNetwork!!.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI))
        assertFalse(download.requiredNetwork!!.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
        assertFalse(download.isRequireDeviceIdle)
        assertEquals(0L, download.minLatencyMillis)
        assertTrue(download.isPersisted)
        assertEquals("v2", download.extras.getString("version"))
        ExpressionSyncJobService.scheduleDownload(context, "scope", "v2")
        assertSame(download, scheduler.allPendingJobs.single { !it.isPeriodic })
        ExpressionSyncJobService.scheduleDownload(context, "scope", "v3")
        assertEquals(2, scheduler.allPendingJobs.size)
        assertEquals("v3", scheduler.allPendingJobs.single { !it.isPeriodic }.extras.getString("version"))
    }

    @Test @Config(sdk = [30]) fun `系统停止参数对象变化仍取消同一下载任务`() {
        val controller = Robolectric.buildService(ExpressionSyncJobService::class.java).create()
        val service = controller.get()
        val parent = SupervisorJob()
        val paused = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) = Unit
        }
        ReflectionHelpers.setField(service, "scope", CoroutineScope(parent + paused))
        val start = org.robolectric.shadow.api.Shadow.newInstanceOf(JobParameters::class.java)
        val stop = org.robolectric.shadow.api.Shadow.newInstanceOf(JobParameters::class.java)
        ReflectionHelpers.setField(start, "jobId", 5175302)
        ReflectionHelpers.setField(stop, "jobId", 5175302)
        try {
            assertTrue(service.onStartJob(start))
            val running = parent.children.single()
            assertTrue(running.isActive)
            assertTrue(service.onStopJob(stop))
            assertTrue("Binder传入不同对象也必须取消", running.isCancelled)
        } finally { controller.destroy() }
    }

    @Test @Config(sdk = [30]) fun `没有指定WiFi网络时拒绝创建下载客户端`() {
        val controller = Robolectric.buildService(ExpressionSyncJobService::class.java).create()
        val params = org.robolectric.shadow.api.Shadow.newInstanceOf(JobParameters::class.java)
        try {
            val method = ExpressionSyncJobService::class.java.getDeclaredMethod("networkClient", JobParameters::class.java, Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }
            val failure = runCatching { method.invoke(controller.get(), params, true) }.exceptionOrNull()
            assertTrue((failure as? java.lang.reflect.InvocationTargetException)?.targetException is java.io.IOException)
        } finally { controller.destroy() }
    }
}
