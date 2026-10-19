package com.yuyan.imemodule.data.collect

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PersistableBundle
import android.os.SystemClock
import com.yuyan.imemodule.data.callrecording.CallRecordingJobService
import com.yuyan.imemodule.data.usage.AppUsageTracker
import kotlinx.coroutines.*
import java.util.UUID

internal data class PendingUploadRecovery(val delayMillis: Long, val wifiOnly: Boolean)

internal object UploadRecovery {
    private const val JOB_ID = 5175340
    private const val SETTLE_MS = 6_000L
    private var observed: Context? = null
    private var startedToken: String? = null
    private var running = false
    private var followUpAt: Long? = null
    private var followUpWifiOnly = true
    private val recoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Synchronized fun observe(context: Context) {
        val app = context.applicationContext
        GameWorkRuntime.bind(app)
        if (observed === app) return
        observed?.let { runCatching { it.unregisterReceiver(receiver) } }
        app.registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        })
        observed = app
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) ImageUploadRuntime.finishInput()
            recoverPending(context)
        }
    }

    /** 启动/屏幕/网络信号只读持久待办，空队列不交给系统唤醒。 */
    fun recoverPending(context: Context): Job = recoveryScope.launch {
        try { scheduleNext(context.applicationContext) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* 不把查询失败转成无条件周期或空网络任务。 */ }
    }

    /** 只由已知持久待办交接一次；不创建空队列周期兜底。 */
    @Synchronized fun wake(context: Context, delayMillis: Long = SETTLE_MS, wifiOnly: Boolean = false) {
        if (!CollectionConsent.enabled(context)) return
        val now = SystemClock.elapsedRealtime()
        val due = now + delayMillis.coerceAtLeast(SETTLE_MS)
        if (running) {
            followUpAt = minOf(followUpAt ?: due, due)
            followUpWifiOnly = followUpWifiOnly && wifiOnly
            return
        }
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        val component = ComponentName(context, UploadRecoveryJobService::class.java)
        val existing = scheduler.getPendingJob(JOB_ID)
        if (existing != null && existing.service != component) return
        val pending = existing?.takeIf { it.extras.getString("token") != startedToken && it.extras.containsKey("due") }
        val combinedWifiOnly = wifiOnly && (pending == null || pending.networkType == JobInfo.NETWORK_TYPE_UNMETERED)
        val combinedDue = minOf(due, pending?.extras?.getLong("due") ?: due)
        val networkType = if (combinedWifiOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY
        if (pending != null && pending.extras.getLong("due") <= due &&
            pending.networkType == networkType && pending.isPersisted) return
        val extras = PersistableBundle().apply {
            putLong("due", combinedDue)
            putString("token", UUID.randomUUID().toString())
        }
        scheduler.schedule(JobInfo.Builder(JOB_ID, component)
            .setRequiredNetworkType(networkType)
            .setBackoffCriteria(300_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .setMinimumLatency((combinedDue - now).coerceAtLeast(SETTLE_MS)).setPersisted(true).setExtras(extras).build())
    }

    internal suspend fun scheduleNext(context: Context, token: String? = null) {
        currentCoroutineContext().ensureActive()
        if (!CollectionConsent.enabled(context)) return
        val collector = DataCollector.pendingRecovery(context)
        val metadataDelay = listOfNotNull(
            com.yuyan.imemodule.data.capture.page.VideoVisitSync.pendingRecoveryDelay(context),
            AppUsageTracker.pendingRecoveryDelay(context),
        ).minOrNull()?.let { if (ImageUploadRuntime.isInputIdle()) it else maxOf(it, 300_000L) }
        val plans = listOfNotNull(collector, metadataDelay?.let { PendingUploadRecovery(it, false) })
        val wifiOnly = plans.all { it.wifiOnly }
        val delay = plans.minOfOrNull {
            if (!wifiOnly && it.wifiOnly && !ImageUploadRuntime.hasValidatedWifi(context)) maxOf(it.delayMillis, 300_000L)
            else it.delayMillis
        }?.let {
            if (GameWorkRuntime.isBackgroundAllowed()) it else maxOf(it, 300_000L)
        }
        currentCoroutineContext().ensureActive()
        synchronized(this) {
            if (token != null && (!running || token != startedToken)) return
            if (delay != null) wake(context, delay, wifiOnly)
        }
    }

    @Synchronized fun started(token: String?) { startedToken = token; running = true }
    @Synchronized internal fun finished(context: Context, token: String? = startedToken) {
        if (!running || token != startedToken) return
        running = false
        val due = followUpAt
        val wifiOnly = followUpWifiOnly
        followUpAt = null
        followUpWifiOnly = true
        if (due != null) wake(context, due - SystemClock.elapsedRealtime(), wifiOnly)
    }
    @Synchronized internal fun stopped(token: String? = startedToken) {
        if (token != startedToken) return
        running = false; followUpAt = null; followUpWifiOnly = true
    }
    @Synchronized fun cancel(context: Context) {
        (context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler).cancel(JOB_ID)
        startedToken = null
        running = false
        followUpAt = null
        followUpWifiOnly = true
    }
}

/** No foreground service or forced wake lock; each uploader retains its own consent/network cadence. */
class UploadRecoveryJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var token: String? = null
    override fun onStartJob(params: JobParameters): Boolean {
        // 同一 Service 被交付新批次时退休旧协程；不全局取消新批次的网络。
        job?.cancel()
        val activeToken = params.extras.getString("token")
        token = activeToken
        UploadRecovery.started(activeToken)
        if (!CollectionConsent.enabled(this)) { UploadRecovery.stopped(); return false }
        job = scope.launch {
            var retry = false
            try {
                withContext(Dispatchers.IO) {
                    CallRecordingJobService.wake(applicationContext)
                    DataCollector.init(applicationContext)
                    val outcomes = supervisorScope {
                        listOf(
                            async { recover { DataCollector.flushNow() } },
                            async { recover { AppUsageTracker.sync(applicationContext) } },
                        ).awaitAll()
                    }
                    retry = outcomes.any { !it }
                    UploadRecovery.scheduleNext(applicationContext, activeToken)
                }
                ensureActive()
            } catch (_: CancellationException) {
                if (!isActive) return@launch // onStopJob owns retry.
                retry = true
            }
            catch (_: Exception) { retry = true }
            jobFinished(params, retry && CollectionConsent.enabled(this@UploadRecoveryJobService))
            UploadRecovery.finished(applicationContext, activeToken)
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        if (params.extras.getString("token") != token) return false
        job?.cancel(); job = null
        UploadRecovery.stopped(params.extras.getString("token"))
        DataCollector.cancelTransfers()
        return CollectionConsent.enabled(this)
    }
    private suspend fun recover(block: suspend () -> Unit): Boolean = try {
        block(); true
    } catch (_: CancellationException) { currentCoroutineContext().ensureActive(); false } catch (_: Exception) { false }

    override fun onDestroy() { scope.cancel(); UploadRecovery.stopped(token); super.onDestroy() }
}
