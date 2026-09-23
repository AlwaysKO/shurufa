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
import android.util.Log
import androidx.preference.PreferenceManager
import com.yuyan.imemodule.data.collect.DataCollector
import com.yuyan.imemodule.data.collect.ServerConfig
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import okhttp3.OkHttpClient

/** 任意网络半小时探测；目录及图片由独立的持久 Wi-Fi 任务下载。 */
class ExpressionSyncJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        if (!PreferenceManager.getDefaultSharedPreferences(this).getBoolean("ai_sticker_enabled", true)) return false
        val download = params.jobId == DOWNLOAD_JOB_ID
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var retry = false
            try {
                withTimeout(2 * 60 * 1000L) {
                    coroutineScope {
                        ServerConfig.init(applicationContext)
                        val sync = ExpressionSync(networkClient(params, download), ServerConfig.baseUrl,
                            DataCollector.deviceId(applicationContext), ExpressionCatalog.fromAssets(applicationContext),
                            ExpressionCache(cacheDir), this, File(filesDir, "expression-catalogs"))
                        if (download) {
                            // 域名、设备或内置目录变更后，旧待办不能作用于新目录。
                            if (params.extras.getString("scope") != sync.backgroundSyncKey) return@coroutineScope
                            val version = params.extras.getString("version") ?: return@coroutineScope
                            val complete = sync.syncInBackground(expectedVersion = version)
                            retry = !complete && sync.backgroundSyncNeeded(sync.currentCatalog().document.version)
                            Log.i(TAG, "Wi-Fi sync complete=$complete version=${sync.currentCatalog().document.version}")
                        } else {
                            val version = sync.remoteVersion() ?: return@coroutineScope
                            if (sync.backgroundSyncNeeded(version)) {
                                scheduleDownload(applicationContext, sync.backgroundSyncKey, version)
                            }
                        }
                    }
                }
            } catch (_: TimeoutCancellationException) {
                retry = download
            } catch (_: CancellationException) {
                return@launch // onStopJob 已接管生命周期。
            } catch (error: Exception) {
                retry = download
                Log.w(TAG, "background sync deferred: ${error.javaClass.simpleName}")
            } finally {
                jobs.remove(params.jobId, coroutineContext.job)
            }
            if (isActive) jobFinished(params, retry)
        }
        jobs.put(params.jobId, job)?.cancel()
        job.start()
        return true
    }

    private fun networkClient(params: JobParameters, download: Boolean): OkHttpClient {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) params.network else cm.activeNetwork
        if (download && (network == null ||
                cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true)) {
            throw IOException("Wi-Fi unavailable")
        }
        return OkHttpClient.Builder().apply {
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
        return params.jobId == DOWNLOAD_JOB_ID
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ExpressionBackgroundSync"
        private const val CHECK_JOB_ID = 5175301
        private const val DOWNLOAD_JOB_ID = 5175302
        private const val INTERVAL_MS = 30 * 60 * 1000L
        private const val SCHEMA = 2

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val current = scheduler.allPendingJobs.firstOrNull { it.id == CHECK_JOB_ID }
            if (current?.intervalMillis == INTERVAL_MS && current.extras.getInt("schema") == SCHEMA) return
            scheduler.schedule(JobInfo.Builder(CHECK_JOB_ID, ComponentName(context, ExpressionSyncJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(INTERVAL_MS)
                .setPersisted(true)
                .setExtras(PersistableBundle().apply { putInt("schema", SCHEMA) })
                .build())
        }

        internal fun scheduleDownload(context: Context, scope: String, version: String) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val current = scheduler.allPendingJobs.firstOrNull { it.id == DOWNLOAD_JOB_ID }
            if (current?.extras?.getString("scope") == scope && current.extras.getString("version") == version) return
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
            scheduler.schedule(builder.build())
        }
    }
}
