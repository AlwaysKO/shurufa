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

internal object UploadRecovery {
    private const val JOB_ID = 5175340
    private const val SETTLE_MS = 6_000L
    private var observed: Context? = null
    private var startedToken: String? = null
    private var running = false
    private var followUpAt: Long? = null

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
            wake(context)
        }
    }

    /** One system-managed handoff after real activity, without replacing the 30-minute fallback. */
    @Synchronized fun wake(context: Context, delayMillis: Long = SETTLE_MS) {
        if (!CollectionConsent.enabled(context)) return
        val delay = delayMillis.coerceAtLeast(SETTLE_MS)
        val due = SystemClock.elapsedRealtime() + delay
        if (running) {
            followUpAt = minOf(followUpAt ?: due, due)
            return
        }
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        val component = ComponentName(context, UploadRecoveryJobService::class.java)
        val existing = scheduler.getPendingJob(JOB_ID)
        if (existing != null && existing.service != component) return
        if (existing != null && existing.extras.getString("token") != startedToken &&
            existing.extras.containsKey("due") && existing.extras.getLong("due") <= due) return
        val extras = PersistableBundle().apply {
            putLong("due", due)
            putString("token", UUID.randomUUID().toString())
        }
        scheduler.schedule(JobInfo.Builder(JOB_ID, component)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setBackoffCriteria(300_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .setMinimumLatency(delay).setExtras(extras).build())
    }

    internal suspend fun scheduleNext(context: Context, token: String? = null) {
        currentCoroutineContext().ensureActive()
        if (!CollectionConsent.enabled(context)) return
        val delay = listOfNotNull(
            DataCollector.nextUploadDelay(context),
            com.yuyan.imemodule.data.capture.page.VideoVisitSync.nextUploadDelay(context, includePaused = true),
            AppUsageTracker.nextUploadDelay(context, includePaused = true),
        ).minOrNull()
        currentCoroutineContext().ensureActive()
        synchronized(this) {
            if (token != null && (!running || token != startedToken)) return
            if (delay != null) wake(context, delay)
        }
    }

    @Synchronized fun started(token: String?) { startedToken = token; running = true }
    @Synchronized internal fun finished(context: Context, token: String? = startedToken) {
        if (!running || token != startedToken) return
        running = false
        val due = followUpAt
        followUpAt = null
        if (due != null) wake(context, due - SystemClock.elapsedRealtime())
    }
    @Synchronized internal fun stopped(token: String? = startedToken) {
        if (token != startedToken) return
        running = false; followUpAt = null
    }
    @Synchronized fun cancel(context: Context) {
        (context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler).cancel(JOB_ID)
        startedToken = null
        running = false
        followUpAt = null
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
