package com.yuyan.imemodule.expression

import android.app.job.JobScheduler
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Robolectric
import org.robolectric.util.ReflectionHelpers
import kotlinx.coroutines.*

@RunWith(RobolectricTestRunner::class)
class ExpressionSyncJobServiceTest {
    @Test @Config(sdk = [30]) fun `手动检查升级已有自动任务且随后自动触发不覆盖手动资格`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        ExpressionSyncJobService.scheduleImmediateCheck(context)
        assertFalse(scheduler.allPendingJobs.single().extras.getBoolean("user_initiated"))
        com.yuyan.imemodule.data.collect.BackgroundRefreshRuntime.request(context, userInitiated = true)
        val manual = scheduler.allPendingJobs.single { it.id == 5175303 }
        assertTrue(manual.extras.getBoolean("user_initiated"))
        assertEquals(0L, manual.minLatencyMillis)
        ExpressionSyncJobService.scheduleImmediateCheck(context)
        assertSame(manual, scheduler.allPendingJobs.single { it.id == 5175303 })
    }

    @Test @Config(sdk = [30]) fun `恢复旧资源下载迁移自己的任务编号与元数据`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        scheduler.schedule(JobInfo.Builder(5175302, ComponentName(context, ExpressionSyncJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED).setPersisted(true)
            .setExtras(android.os.PersistableBundle().apply { putString("scope", "old"); putString("version", "v4") }).build())
        ExpressionSyncJobService.recover(context)
        assertFalse(scheduler.allPendingJobs.any { it.id == 5175302 })
        val migrated = scheduler.allPendingJobs.single { it.id == 5175332 }
        assertEquals("old", migrated.extras.getString("scope"))
        assertEquals("v4", migrated.extras.getString("version"))
    }

    @Test @Config(sdk = [30]) fun `升级先启动应用使用再迁移旧资源后两类持久任务都存在`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(com.yuyan.imemodule.data.collect.CollectionConsent.KEY, true)
            .putBoolean(com.yuyan.imemodule.data.usage.AppUsageTracker.KEY, true).commit()
        val legacy = JobInfo.Builder(5175302, ComponentName(context, ExpressionSyncJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED).setPersisted(true)
            .setExtras(android.os.PersistableBundle().apply { putString("scope", "old"); putString("version", "v4") }).build()
        scheduler.schedule(legacy)
        // DataCollector.init first starts Usage, then starts shared resource recovery.
        com.yuyan.imemodule.data.usage.UsageSyncJobService.schedule(context)
        assertSame(legacy, scheduler.allPendingJobs.single { it.id == 5175302 })
        ExpressionSyncJobService.recover(context)
        val usage = scheduler.allPendingJobs.firstOrNull { it.id == 5175302 }
        assertNotNull("迁移释放旧ID后必须恢复已开启的Usage系统兜底", usage)
        assertEquals(ComponentName(context, com.yuyan.imemodule.data.usage.UsageSyncJobService::class.java), usage!!.service)
        assertTrue(usage.isPersisted)
        assertEquals(30 * 60_000L, usage.intervalMillis)
        val migrated = scheduler.allPendingJobs.single { it.id == 5175332 }
        assertEquals("old", migrated.extras.getString("scope"))
        assertEquals("v4", migrated.extras.getString("version"))
        ExpressionSyncJobService.recover(context)
        assertSame(usage, scheduler.allPendingJobs.single { it.id == 5175302 })
        assertSame(migrated, scheduler.allPendingJobs.single { it.id == 5175332 })
    }

    @Test @Config(sdk = [30]) fun `取消应用使用任务不能删除尚未迁移的旧资源任务`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        val legacy = JobInfo.Builder(5175302, ComponentName(context, ExpressionSyncJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED).setPersisted(true).build()
        scheduler.schedule(legacy)
        com.yuyan.imemodule.data.usage.UsageSyncJobService.cancel(context)
        assertSame(legacy, scheduler.allPendingJobs.firstOrNull { it.id == 5175302 })
    }

    @Test @Config(sdk = [30]) fun `取消应用使用仍删除自己任务并保留已迁移资源下载`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        com.yuyan.imemodule.data.usage.UsageSyncJobService.schedule(context)
        ExpressionSyncJobService.scheduleDownload(context, "scope", "v2")
        val resource = scheduler.allPendingJobs.single { it.id == 5175332 }
        com.yuyan.imemodule.data.usage.UsageSyncJobService.cancel(context)
        assertFalse(scheduler.allPendingJobs.any { it.id == 5175302 })
        assertSame(resource, scheduler.allPendingJobs.single { it.id == 5175332 })
    }

    @Test @Config(sdk = [30]) fun `移动网络恢复在两小时内不补发自动版本检查`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = manager.getNetworkCapabilities(manager.activeNetwork)!!
        org.robolectric.Shadows.shadowOf(caps).removeTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
        org.robolectric.Shadows.shadowOf(caps).addTransportType(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)
        ExpressionSyncJobService.recordCheckAttempt(context, "scope")
        context.getSharedPreferences("expression_background_sync", Context.MODE_PRIVATE).edit()
            .putLong("last_attempt_at", System.currentTimeMillis() - 30 * 60_000L).commit()
        assertFalse(ExpressionSyncJobService.checkDue(context, "scope"))
        com.yuyan.imemodule.data.collect.BackgroundRefreshRuntime.networkChanged(context)
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        assertFalse(scheduler.allPendingJobs.any { it.id == 5175303 })
        context.getSharedPreferences("expression_background_sync", Context.MODE_PRIVATE).edit()
            .putLong("last_attempt_at", System.currentTimeMillis() - 2 * 60 * 60_000L).commit()
        com.yuyan.imemodule.data.collect.BackgroundRefreshRuntime.networkChanged(context)
        assertTrue(scheduler.allPendingJobs.any { it.id == 5175303 })
    }

    @Test @Config(sdk = [30]) fun `断网恢复只保留系统兜底不创建立即检查`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        org.robolectric.Shadows.shadowOf(manager).setActiveNetworkInfo(null)
        ExpressionSyncJobService.recover(context)
        val jobs = (context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler).allPendingJobs
        assertEquals(listOf(5175301), jobs.map { it.id })
    }

    @Test @Config(sdk = [30]) fun `资源下载不覆盖同ID的应用使用同步`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        val usage = JobInfo.Builder(5175302, ComponentName(context, com.yuyan.imemodule.data.usage.UsageSyncJobService::class.java))
            .setPeriodic(30 * 60_000L).setPersisted(true).build()
        scheduler.schedule(usage)
        ExpressionSyncJobService.scheduleDownload(context, "scope", "v2")
        assertSame(usage, scheduler.allPendingJobs.single { it.id == 5175302 })
        assertEquals("v2", scheduler.allPendingJobs.single { it.id == 5175332 }.extras.getString("version"))
    }

    @Before fun resetProcessMonitor() {
        // Robolectric 重建 Context/Looper，但 Kotlin companion 的进程状态会跨用例保留。
        ReflectionHelpers.setStaticField(ExpressionSyncJobService::class.java, "monitoring", false)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val shadow = org.robolectric.Shadows.shadowOf(manager)
        shadow.setActiveNetworkInfo(org.robolectric.shadows.ShadowNetworkInfo.newInstance(
            android.net.NetworkInfo.DetailedState.CONNECTED, android.net.ConnectivityManager.TYPE_WIFI, 0, true, true))
        val caps = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        org.robolectric.Shadows.shadowOf(caps).addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
        org.robolectric.Shadows.shadowOf(caps).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        org.robolectric.Shadows.shadowOf(caps).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        shadow.setNetworkCapabilities(manager.activeNetwork, caps)
    }

    @Test @Config(sdk = [30]) fun `版本请求失败后半小时内不因反复打开键盘而重试`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        ExpressionSyncJobService.recordCheckAttempt(context, "scope")
        ExpressionSyncJobService.scheduleDownload(context, "scope", "v2")
        scheduler.cancelAll()
        repeat(3) { ExpressionSyncJobService.recover(context) }
        assertFalse(ExpressionSyncJobService.checkDue(context, "scope"))
        assertTrue(ExpressionSyncJobService.checkDue(context, "other-scope"))
        assertEquals(setOf(5175301, 5175332), scheduler.allPendingJobs.map { it.id }.toSet())
    }

    @Test @Config(sdk = [30]) fun `无人输入时半小时巡检恢复被移除的任务`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        ExpressionSyncJobService.startRecovery(context)
        ExpressionSyncJobService.recordCheck(context, "scope", "v2")
        ExpressionSyncJobService.scheduleDownload(context, "scope", "v2")
        scheduler.cancelAll()
        // Handler 使用虚拟 uptime；版本节流使用持久化 wall clock，单独推进其记录。
        context.getSharedPreferences("expression_background_sync", Context.MODE_PRIVATE).edit()
            .putLong("last_attempt_at", System.currentTimeMillis() - 30 * 60 * 1000L).commit()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(30, java.util.concurrent.TimeUnit.MINUTES)
        assertEquals(setOf(5175301, 5175332, 5175303), scheduler.allPendingJobs.map { it.id }.toSet())
    }

    @Test @Config(sdk = [30]) fun `共享网络恢复补回下载且资源模块不另建监听`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        ExpressionSyncJobService.startRecovery(context)
        ExpressionSyncJobService.startRecovery(context)
        val callbacks = org.robolectric.Shadows.shadowOf(cm).networkCallbacks
        assertEquals(0, callbacks.size)
        ExpressionSyncJobService.recordCheck(context, "scope", "v2")
        ExpressionSyncJobService.scheduleDownload(context, "scope", "v2")
        scheduler.cancelAll()
        com.yuyan.imemodule.data.collect.BackgroundRefreshRuntime.networkChanged(context)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(setOf(5175301, 5175332), scheduler.allPendingJobs.map { it.id }.toSet())
    }

    @Test @Config(sdk = [30]) fun `任务被清空后恢复周期检查和已持久化的WiFi下载`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        scheduler.cancelAll()
        ExpressionSyncJobService.scheduleDownload(context, "scope", "v2")
        scheduler.cancelAll()
        ExpressionSyncJobService.recover(context)
        val jobs = scheduler.allPendingJobs
        assertEquals(30 * 60 * 1000L, jobs.single { it.isPeriodic }.intervalMillis)
        val download = jobs.single { it.id == 5175332 }
        assertEquals("v2", download.extras.getString("version"))
        assertTrue(download.requiredNetwork!!.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI))
        assertNotNull(jobs.singleOrNull { it.id == 5175303 })
        ExpressionSyncJobService.recover(context)
        assertSame(download, scheduler.allPendingJobs.single { it.id == 5175332 })
    }

    @Test @Config(sdk = [30]) fun `刚检查过只恢复持久任务不重复立即探测`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        scheduler.cancelAll()
        ExpressionSyncJobService.recordCheck(context, "scope", "v3")
        assertFalse(ExpressionSyncJobService.checkDue(context, "scope"))
        assertTrue(ExpressionSyncJobService.checkDue(context, "another-endpoint"))
        ExpressionSyncJobService.recover(context)
        assertNull(scheduler.allPendingJobs.firstOrNull { it.id == 5175303 })
        assertTrue(scheduler.allPendingJobs.any { it.isPeriodic })
    }

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
        ReflectionHelpers.setField(start, "jobId", 5175332)
        ReflectionHelpers.setField(stop, "jobId", 5175332)
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
