package com.yuyan.imemodule.expression

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.PersistableBundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.preference.PreferenceManager
import com.yuyan.imemodule.data.collect.DataCollector
import com.yuyan.imemodule.data.collect.BackgroundRefreshRuntime
import com.yuyan.imemodule.data.collect.BackgroundRefreshPolicy
import com.yuyan.imemodule.data.collect.ImageUploadRuntime
import com.yuyan.imemodule.data.collect.GameWorkRuntime
import com.yuyan.imemodule.data.collect.ServerConfig
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import okhttp3.OkHttpClient

/** 共享词库/配置/资源检查批次；目录及图片仍由独立的持久 Wi-Fi 任务下载。 */
class ExpressionSyncJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        val download = params.jobId == DOWNLOAD_JOB_ID || params.jobId == LEGACY_DOWNLOAD_JOB_ID
        if (download && !PreferenceManager.getDefaultSharedPreferences(this).getBoolean("ai_sticker_enabled", true)) return false
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var retry = false
            try {
                withTimeout(2 * 60 * 1000L) {
                    if (!GameWorkRuntime.isBackgroundAllowed()) { retry = true; return@withTimeout }
                    if (!download) {
                        if (!ImageUploadRuntime.isBackgroundWorkAllowed()) { retry = true; return@withTimeout }
                        BackgroundRefreshRuntime.refreshNow(applicationContext, params.extras.getBoolean("user_initiated")) {
                            checkVersionInBatch(params)
                        }
                        return@withTimeout
                    }
                    coroutineScope {
                        ServerConfig.init(applicationContext)
                        val sync = ExpressionSync(networkClient(params, download), ServerConfig.baseUrl,
                            DataCollector.deviceId(applicationContext), ExpressionCatalog.fromAssets(applicationContext),
                            ExpressionCache(cacheDir), this, File(filesDir, "expression-catalogs"),
                            backgroundAllowed = GameWorkRuntime::isBackgroundAllowed)
                        if (download) {
                            // 域名、设备或内置目录变更后，旧待办不能作用于新目录。
                            if (params.extras.getString("scope") != sync.backgroundSyncKey) {
                                clearPending(applicationContext, params.extras.getString("scope"), params.extras.getString("version"))
                                scheduleImmediateCheck(applicationContext)
                                return@coroutineScope
                            }
                            val version = params.extras.getString("version") ?: return@coroutineScope
                            val complete = sync.syncInBackground(expectedVersion = version)
                            if (!GameWorkRuntime.isBackgroundAllowed()) { retry = true; return@coroutineScope }
                            retry = !complete && sync.backgroundSyncNeeded(sync.backgroundVersion)
                            state(applicationContext).edit().putLong("last_download_at", System.currentTimeMillis())
                                .putString("last_download_result", if (complete) "complete" else "pending").apply()
                            if (complete) clearPending(applicationContext, sync.backgroundSyncKey, version)
                            Log.i(TAG, "Wi-Fi sync complete=$complete version=${sync.currentCatalog().document.version}")
                        }
                    }
                }
            } catch (_: TimeoutCancellationException) {
                retry = download
            } catch (_: CancellationException) {
                return@launch // onStopJob 已接管生命周期。
            } catch (error: Exception) {
                retry = download || !GameWorkRuntime.isBackgroundAllowed()
                Log.w(TAG, "background sync deferred: ${error.javaClass.simpleName}")
            } finally {
                jobs.remove(params.jobId, coroutineContext.job)
            }
            if (!download && !GameWorkRuntime.isBackgroundAllowed()) {
                // 游戏取消不是一次已完成的版本检查，退出游戏后仍可及时补查。
                state(applicationContext).edit().remove("last_attempt_at").apply()
                retry = true
            }
            if (isActive) jobFinished(params, retry)
        }
        jobs.put(params.jobId, job)?.cancel()
        job.start()
        return true
    }

    private suspend fun checkVersionInBatch(params: JobParameters) = coroutineScope {
        if (!PreferenceManager.getDefaultSharedPreferences(applicationContext).getBoolean("ai_sticker_enabled", true)) return@coroutineScope
        if (!ImageUploadRuntime.hasValidatedNetwork(applicationContext) || !ImageUploadRuntime.isBackgroundWorkAllowed()) return@coroutineScope
        ServerConfig.init(applicationContext)
        val sync = ExpressionSync(networkClient(params, false), ServerConfig.baseUrl,
            DataCollector.deviceId(applicationContext), ExpressionCatalog.fromAssets(applicationContext),
            ExpressionCache(cacheDir), this, File(filesDir, "expression-catalogs"),
            backgroundAllowed = ImageUploadRuntime::isBackgroundWorkAllowed)
        recordCheckAttempt(applicationContext, sync.backgroundSyncKey)
        val version = sync.remoteVersion(recommendationsOnly = true, allowed = ImageUploadRuntime::isBackgroundWorkAllowed)
            ?: return@coroutineScope
        // 原窗口检查覆盖整个目录；合并后仍须发现仅合成底图变更的版本。
        val fullVersion = sync.remoteVersion(allowed = ImageUploadRuntime::isBackgroundWorkAllowed)
        if (!ImageUploadRuntime.isBackgroundWorkAllowed()) return@coroutineScope
        recordCheck(applicationContext, sync.backgroundSyncKey, version)
        val missingRecommendations = sync.backgroundSyncNeeded(version) // 同时恢复已持久化目录。
        val changedCatalog = fullVersion != null && sync.currentCatalog().document.version != fullVersion
        if (changedCatalog || missingRecommendations) scheduleDownload(applicationContext, sync.backgroundSyncKey,
            if (changedCatalog) fullVersion!! else version)
    }

    private fun networkClient(params: JobParameters, download: Boolean): OkHttpClient {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) params.network else cm.activeNetwork
        if (download && (network == null ||
                cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true)) {
            throw IOException("Wi-Fi unavailable")
        }
        return OkHttpClient.Builder().addInterceptor(GameWorkRuntime.interceptor).apply {
            if (network != null) {
                // 网络切换后请求失败并保留待办，不回退到默认移动网络。
                socketFactory(network.socketFactory)
                dns(object : okhttp3.Dns {
                    override fun lookup(hostname: String) = network.getAllByName(hostname).toList()
                })
            }
        }.build()
    }

    override fun onStopJob(params: JobParameters): Boolean {
        jobs.remove(params.jobId)?.cancel()
        return params.jobId == DOWNLOAD_JOB_ID || params.jobId == LEGACY_DOWNLOAD_JOB_ID
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ExpressionBackgroundSync"
        private const val CHECK_JOB_ID = 5175301
        private const val LEGACY_DOWNLOAD_JOB_ID = 5175302
        private const val DOWNLOAD_JOB_ID = 5175332
        private const val IMMEDIATE_CHECK_JOB_ID = 5175303
        private const val INTERVAL_MS = 30 * 60 * 1000L
        private const val SCHEMA = 3
        private var monitoring = false
        private fun state(context: Context) = context.getSharedPreferences("expression_background_sync", Context.MODE_PRIVATE)

        /** 不依赖词库初始化或某个关键词输入；进程存活时恢复被系统移除的任务。 */
        @Synchronized fun startRecovery(context: Context) {
            val app = context.applicationContext
            recover(app)
            if (monitoring) return
            monitoring = true
            val handler = Handler(Looper.getMainLooper())
            val tick = object : Runnable {
                override fun run() { recover(app); handler.postDelayed(this, INTERVAL_MS) }
            }
            handler.postDelayed(tick, INTERVAL_MS)
            // 网络恢复由 Collector 已有监听转发到 BackgroundRefreshRuntime，避免重复注册。
        }

        @Synchronized internal fun recover(context: Context) {
            ServerConfig.init(context)
            schedule(context)
            val saved = state(context)
            val legacy = (context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler).allPendingJobs.firstOrNull {
                it.id == LEGACY_DOWNLOAD_JOB_ID && it.service == ComponentName(context, ExpressionSyncJobService::class.java)
            }
            val scope = saved.getString("pending_scope", null) ?: legacy?.extras?.getString("scope")
            val version = saved.getString("pending_version", null) ?: legacy?.extras?.getString("version")
            if (scope != null && version != null) scheduleDownload(context, scope, version)
            if (BackgroundRefreshRuntime.due(context)) scheduleImmediateCheck(context)
        }

        internal fun checkDue(context: Context, scope: String): Boolean {
            val saved = state(context)
            val last = if (saved.getString("attempt_scope", null) == scope && saved.contains("last_attempt_at")) saved.getLong("last_attempt_at", 0L) else null
            return BackgroundRefreshPolicy.due(System.currentTimeMillis(), last,
                ImageUploadRuntime.hasValidatedNetwork(context), ImageUploadRuntime.hasValidatedWifi(context))
        }

        internal fun recordCheckAttempt(context: Context, scope: String) {
            state(context).edit().putLong("last_attempt_at", System.currentTimeMillis())
                .putString("attempt_scope", scope).commit()
        }

        internal fun recordCheck(context: Context, scope: String, version: String) {
            recordCheckAttempt(context, scope)
            state(context).edit().putLong("last_check_at", System.currentTimeMillis())
                .putString("checked_scope", scope).putString("checked_version", version).commit()
        }

        @Synchronized private fun clearPending(context: Context, scope: String?, version: String?) {
            val saved = state(context)
            if (saved.getString("pending_scope", null) == scope && saved.getString("pending_version", null) == version) {
                saved.edit().remove("pending_scope").remove("pending_version").commit()
            }
        }

        @Synchronized internal fun scheduleImmediateCheck(context: Context, userInitiated: Boolean = false) {
            if (!ImageUploadRuntime.hasValidatedNetwork(context)) return
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val component = ComponentName(context, ExpressionSyncJobService::class.java)
            val current = scheduler.allPendingJobs.firstOrNull { it.id == IMMEDIATE_CHECK_JOB_ID }
            if (current != null && (current.service != component || !userInitiated || current.extras.getBoolean("user_initiated"))) return
            val result = scheduler.schedule(JobInfo.Builder(IMMEDIATE_CHECK_JOB_ID, component)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
                .setExtras(PersistableBundle().apply { putBoolean("user_initiated", userInitiated) }).build())
            state(context).edit().putInt("check_schedule_result", result).apply()
        }

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val current = scheduler.allPendingJobs.firstOrNull { it.id == CHECK_JOB_ID }
            if (current != null && current.service != ComponentName(context, ExpressionSyncJobService::class.java)) return
            if (current?.intervalMillis == INTERVAL_MS && current.extras.getInt("schema") == SCHEMA) return
            val result = scheduler.schedule(JobInfo.Builder(CHECK_JOB_ID, ComponentName(context, ExpressionSyncJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(INTERVAL_MS)
                .setPersisted(true)
                .setExtras(PersistableBundle().apply { putInt("schema", SCHEMA) })
                .build())
            state(context).edit().putInt("periodic_schedule_result", result).apply()
        }

        @Synchronized internal fun scheduleDownload(context: Context, scope: String, version: String) {
            val saved = state(context)
            if (saved.getString("pending_scope", null) != scope || saved.getString("pending_version", null) != version) {
                saved.edit().putString("pending_scope", scope).putString("pending_version", version).commit()
            }
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val current = scheduler.allPendingJobs.firstOrNull { it.id == DOWNLOAD_JOB_ID }
            val component = ComponentName(context, ExpressionSyncJobService::class.java)
            if (current != null && current.service != component) return
            fun cancelLegacy() {
                if (scheduler.allPendingJobs.any { it.id == LEGACY_DOWNLOAD_JOB_ID && it.service == component }) {
                    scheduler.cancel(LEGACY_DOWNLOAD_JOB_ID)
                    // Usage starts before resource recovery and may have deferred while this ID was occupied.
                    if (com.yuyan.imemodule.data.usage.AppUsageTracker.enabled(context)) {
                        com.yuyan.imemodule.data.usage.UsageSyncJobService.schedule(context)
                    }
                }
            }
            if (current?.extras?.getString("scope") == scope && current.extras.getString("version") == version) { cancelLegacy(); return }
            val builder = JobInfo.Builder(DOWNLOAD_JOB_ID, ComponentName(context, ExpressionSyncJobService::class.java))
                .setPersisted(true)
                .setBackoffCriteria(5 * 60 * 1000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .setExtras(PersistableBundle().apply { putString("scope", scope); putString("version", version) })
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                builder.setRequiredNetwork(NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build())
            } else {
                builder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) builder.setRequiresStorageNotLow(true)
            val result = scheduler.schedule(builder.build())
            if (result == JobScheduler.RESULT_SUCCESS) cancelLegacy()
            saved.edit().putInt("download_schedule_result", result).apply()
        }
    }
}
