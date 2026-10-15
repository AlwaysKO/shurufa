package com.yuyan.imemodule.data.usage

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.preference.PreferenceManager
import com.yuyan.imemodule.data.collect.AppNameResolver
import com.yuyan.imemodule.data.collect.CollectionConsent
import com.yuyan.imemodule.data.collect.GameWorkRuntime
import com.yuyan.imemodule.data.collect.DataCollector
import com.yuyan.imemodule.data.collect.ServerConfig
import com.yuyan.imemodule.data.collect.collectorTargetGate
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Opt-in usage metadata only; no accessibility/page contents or installed-app enumeration. */
object AppUsageTracker {
    const val KEY = "app_usage_enabled_v1"
    private const val DAY = 86_400_000L
    // Android queues usage events asynchronously. This bounds common races, not all OEM delays.
    private const val SETTLING_MS = 5_000L
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val sampleLock=Any()
    private val syncMutex=Mutex()
    private val syncCadence=com.yuyan.imemodule.data.collect.CollectorSyncCadence(wifiIntervalMs=300_000L, mobileIntervalMs=300_000L, now=SystemClock::elapsedRealtime)
    private val http=OkHttpClient.Builder().addInterceptor(GameWorkRuntime.interceptor).connectTimeout(5,TimeUnit.SECONDS).readTimeout(10,TimeUnit.SECONDS).callTimeout(15,TimeUnit.SECONDS).build()
    private val names=AppNameResolver()
    private var loop: Job?=null
    private var processSampled=false
    @Volatile private var appContext: Context?=null
    private val uploads=UsageUploadGate { com.yuyan.imemodule.data.collect.ImageUploadRuntime.isBackgroundWorkAllowed() && appContext?.let { enabled(it) && hasPermission(it) } == true }
    private fun requested(context: Context)=PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY,false)
    fun enabled(context: Context)=requested(context) && CollectionConsent.enabled(context)
    @Suppress("DEPRECATION")
    fun hasPermission(context: Context): Boolean = try {
        val ops=context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,Process.myUid(),context.packageName)==AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) { false }

    fun setEnabled(context: Context, value: Boolean, now: Long=System.currentTimeMillis()): Boolean {
        appContext=context.applicationContext
        if(value && (!CollectionConsent.enabled(context) || !hasPermission(context))) return false
        synchronized(sampleLock) {
            val prefs=PreferenceManager.getDefaultSharedPreferences(context)
            // Opt-out cannot depend on a healthy database; opening requires a durable baseline.
            val saved=prefs.edit().putBoolean(KEY,false).commit()
            uploads.cancel()
            UsageSyncJobService.cancel(context)
            try {
                resetBaseline(context,now,!value)
            } catch (_: Exception) {
                Log.w("AppUsage","Usage baseline unavailable; collection remains off")
                return !value && saved
            }
            processSampled=true
            if(!value) return saved
            if(!prefs.edit().putBoolean(KEY,true).commit()) return false
        }
        UsageSyncJobService.schedule(context)
        return true
    }

    private fun resetBaseline(context: Context, now: Long, suspended: Boolean) {
        ServerConfig.init(context)
        UsageStore(context).use { store ->
            val r=store.state()?.let { UsageSessionEngine.gap(it,now,"collection_paused") }
                ?: UsageReduction(UsageState(cursor=now),emptyList())
            store.save(r.copy(state=r.state.copy(suspended=suspended,boot=boot(context),elapsed=SystemClock.elapsedRealtime(),observedAt=now)),ServerConfig.eventTargets)
        }
    }

    /** Errors here must never prevent the master switch from stopping other collectors. */
    fun masterConsentChanged(context: Context) {
        if(!requested(context)) return
        uploads.cancel()
        UsageSyncJobService.cancel(context)
        synchronized(sampleLock) {
            try {
                resetBaseline(context,System.currentTimeMillis(),!CollectionConsent.enabled(context))
            } catch (_: Exception) {
                PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(KEY,false).commit()
                Log.w("AppUsage","Usage state unavailable; explicit re-enabling required")
                return
            }
        }
        if(enabled(context)) UsageSyncJobService.schedule(context)
    }

    @Synchronized fun start(context: Context) {
        val app=context.applicationContext
        appContext=app
        if(enabled(app)) UsageSyncJobService.schedule(app)
        if(loop!=null) return
        loop=scope.launch {
            while(isActive) {
                if(enabled(app)) runCatching { sync(app) }.onFailure { if(it is CancellationException) throw it; Log.w("AppUsage","Usage sync deferred",it) }
                delay(30_000)
            }
        }
    }
    private fun boot(context: Context): Int = if(Build.VERSION.SDK_INT>=24) Settings.Global.getInt(context.contentResolver,Settings.Global.BOOT_COUNT,-1) else -1

    internal fun sample(context: Context, now: Long=System.currentTimeMillis()) = synchronized(sampleLock) {
        if(!enabled(context)) return@synchronized
        ServerConfig.init(context)
        UsageStore(context).use { store ->
            val elapsed=SystemClock.elapsedRealtime(); val boot=boot(context)
            val original=store.state() ?: UsageState(cursor=now,boot=boot,elapsed=elapsed,observedAt=now)
            val targets=ServerConfig.eventTargets
            fun saveAndHandoff(result: UsageReduction, events: List<UsageEvent> = emptyList()) {
                val evidenceChanged=store.save(result, targets, events)
                // 只有实际新记录/原始证据成功落盘才交给系统；不在 syncMutex 内递归查询 nextUploadDelay。
                if ((result.records.isNotEmpty() || evidenceChanged) && enabled(context) && hasPermission(context)) runCatching {
                    val delay = syncCadence.wakeDelay(com.yuyan.imemodule.data.collect.ImageUploadRuntime.hasValidatedWifi(context))
                    val paused = !com.yuyan.imemodule.data.collect.ImageUploadRuntime.isBackgroundWorkAllowed()
                    com.yuyan.imemodule.data.collect.UploadRecovery.wake(context, if (paused) maxOf(delay, 300_000L) else delay)
                }.onFailure { Log.w("AppUsage", "Usage recovery handoff deferred") }
            }
            fun saveGap(reason: String, suspended: Boolean) {
                val r=UsageSessionEngine.gap(original,now,reason)
                saveAndHandoff(r.copy(state=r.state.copy(suspended=suspended,boot=boot,elapsed=elapsed,observedAt=now)))
            }
            if(!hasPermission(context)) {
                if(!original.suspended) saveGap("permission_lost",true)
                return@use
            }
            val discontinuity=usageDiscontinuity(original,now,elapsed,boot,!processSampled)
            processSampled=true
            if(discontinuity!=null) { saveGap(discontinuity,false); return@use }
            if(original.suspended) { saveGap("query_unavailable",false); return@use }
            val until=now-SETTLING_MS
            if(until<=original.cursor) return@use
            val records=mutableListOf<UsageRecord>()
            var state=original
            if(now-state.cursor>DAY) {
                val gap=UsageSessionEngine.gap(state,now-DAY,"history_gap")
                records.addAll(gap.records); state=gap.state
            }
            val events=try { query(context,state.cursor,until) } catch (_: Exception) {
                saveGap("query_unavailable",true); return@use
            }
            if(!enabled(context) || !hasPermission(context)) return@use
            val result=UsageSessionEngine.reduce(state,events,until)
            records.addAll(result.records)
            val homePackages=usageHomePackages(context)
            saveAndHandoff(result.copy(state=result.state.copy(boot=boot,elapsed=elapsed,observedAt=now),records=records.filter { shouldReportUsage(it,homePackages) }.map { r ->
                r.copy(appName=r.packageName?.let { names.resolve(context,it) })
            }), events)
            store.prune(now-7*DAY,ServerConfig.baseUrl)
        }
    }

    @Suppress("DEPRECATION")
    private fun query(context: Context, from: Long, to: Long): List<UsageEvent> {
        val manager=context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val stream=manager.queryEvents(from,to) ?: error("usage log unavailable")
        val event=UsageEvents.Event(); val result=mutableListOf<UsageEvent>(); var count=0
        while(stream.hasNextEvent()) {
            check(++count<=50_000) { "usage log exceeds safe window" }
            stream.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.USER_INTERACTION && event.timeStamp in from..to) {
                com.yuyan.imemodule.data.collect.HumanInteractionRuntime.usageInteraction(context, event.timeStamp)
            }
            val type=when(event.eventType) {
                1 -> "resume"; 2 -> "pause"; 23 -> "stop"
                16 -> "off"; 15 -> "on"; 17 -> "lock"; 18 -> "unlock"
                26 -> "shutdown"; 27 -> "startup"; else -> null
            } ?: continue
            val token=event.className.orEmpty() // Public API has no activity instance ID.
            result.add(UsageEvent(event.timeStamp,type,event.packageName,token))
        }
        return result
    }

    internal fun acceptsReceipt(body: String, received: Int): Boolean = try {
        val j=JSONObject(body); j.optBoolean("ok") && !j.optBoolean("discarded") && j.optInt("received",-1)==received
    } catch (_: Exception) { false }

    /** Keep a pending batch's existing cadence when the screen-off job runs before it is due. */
    suspend fun nextUploadDelay(context: Context, includePaused: Boolean = false): Long? = withContext(Dispatchers.IO) {
        fun allowed() = enabled(context) && hasPermission(context) &&
            com.yuyan.imemodule.data.collect.ImageUploadRuntime.isBackgroundWorkAllowed() &&
            com.yuyan.imemodule.data.collect.ImageUploadRuntime.hasValidatedNetwork(context)
        // 系统恢复暂停时仅查目标关系，不解码记录或枚举应用；五分钟再判实际可上报记录。
        if (includePaused && enabled(context) && hasPermission(context) &&
            com.yuyan.imemodule.data.collect.ImageUploadRuntime.hasValidatedNetwork(context) &&
            !com.yuyan.imemodule.data.collect.ImageUploadRuntime.isBackgroundWorkAllowed()) {
            val gate = collectorTargetGate(context, ServerConfig.baseUrl)
            return@withContext UsageStore(context).use { store ->
                300_000L.takeIf { store.targets().any(gate::canAttempt) }
            }
        }
        if (!allowed()) return@withContext null
        syncMutex.withLock {
            if (!allowed()) return@withLock null
            ServerConfig.init(context)
            val gate=collectorTargetGate(context,ServerConfig.baseUrl)
            val homePackages=usageHomePackages(context)
            val capabilities=UsageEvidenceCapabilities(context)
            val cadence=syncCadence.wakeDelay(com.yuyan.imemodule.data.collect.ImageUploadRuntime.hasValidatedWifi(context)).coerceAtLeast(30_000L)
            val pendingDelay=UsageStore(context).use { store ->
                store.targets().mapNotNull { target ->
                    if(!allowed() || !gate.canAttempt(target)) return@mapNotNull null
                    val records=store.pending(target).any { shouldReportUsage(it,homePackages) }
                    when {
                        records -> cadence
                        !store.hasPendingEvidence(target) -> null
                        capabilities.supported(target)==false -> maxOf(cadence,capabilities.retryDelay(target))
                        else -> cadence
                    }
                }.minOrNull()
            }
            if (!allowed()) null else pendingDelay
        }
    }

    suspend fun sync(context: Context, userInitiated: Boolean = false) = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            appContext=context.applicationContext
            if(!enabled(context)) return@withLock
            sample(context)
            if(!hasPermission(context) || !com.yuyan.imemodule.data.collect.ImageUploadRuntime.isBackgroundWorkAllowed() ||
                !com.yuyan.imemodule.data.collect.collectorNetworkAvailable(context)) return@withLock
            // 未到期时连本地目标健康探测也不执行；只读检查不消费空队列资格。
            if(!userInitiated && syncCadence.remainingDelay(com.yuyan.imemodule.data.collect.ImageUploadRuntime.hasValidatedWifi(context))>0) return@withLock
            ServerConfig.init(context)
            val gate=collectorTargetGate(context,ServerConfig.baseUrl)
            val homePackages=usageHomePackages(context)
            UsageStore(context).use { store ->
                var batchStarted=false
                for(target in store.targets()) {
                    currentCoroutineContext().ensureActive()
                    if(!enabled(context) || !hasPermission(context) || !com.yuyan.imemodule.data.collect.ImageUploadRuntime.isBackgroundWorkAllowed()) break
                    val batch=store.pendingForUpload(target,homePackages)
                    val capabilities=UsageEvidenceCapabilities(context)
                    val pendingEvidence=store.pendingEvidence(target)
                    val supported=capabilities.supported(target)
                    if((batch.isEmpty() && (pendingEvidence==null || supported==false)) || !gate.canAttempt(target)) continue
                    // 空队列、被过滤记录及无网络/USB资格不消费；健康探测失败也占本批，避免30秒反复联网。
                    if(!batchStarted) {
                        if(!syncCadence.tryStart(com.yuyan.imemodule.data.collect.ImageUploadRuntime.hasValidatedWifi(context),userInitiated)) break
                        batchStarted=true
                    }
                    // 单个目标健康失败不阻塞本批其他目标；只有实际回执才能清理待传。
                    if(!gate.canUpload(target)) continue
                    try {
                        val deviceId=DataCollector.deviceId(context)
                        val evidence=pendingEvidence?.takeIf { supported ?: capabilities.fetch(http,target,deviceId,uploads) }
                        val receipt=uploadUsageEvidenceBatch(http,target,deviceId,batch,evidence,
                            com.yuyan.imemodule.data.collect.HumanInteractionRuntime.snapshot(context),uploads)
                        if(receipt.recordsAccepted && batch.isNotEmpty()) store.acknowledge(target,batch.map { it.id },ServerConfig.baseUrl)
                        if(evidence!=null && receipt.evidenceDisposition!=null) store.acknowledgeEvidence(target,evidence.version,receipt.evidenceDisposition)
                    } catch (e: Exception) {
                        if(e is CancellationException) throw e
                        // Retain this target's batch; an unavailable server must not block the other.
                        Log.w("AppUsage","Usage upload deferred")
                    }
                }
            }
        }
    }
}

/** Only an explicit matching receipt can release this target's durable queue. */
internal fun uploadUsageBatch(http: OkHttpClient, target: String, deviceId: String, batch: List<UsageRecord>, gate: UsageUploadGate? = null): Boolean {
    return uploadUsageEvidenceBatch(http,target,deviceId,batch,null,gate=gate).recordsAccepted
}
