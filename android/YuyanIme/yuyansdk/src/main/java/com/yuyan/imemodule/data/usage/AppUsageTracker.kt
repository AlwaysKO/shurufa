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
import com.yuyan.imemodule.data.collect.DataCollector
import com.yuyan.imemodule.data.collect.ServerConfig
import com.yuyan.imemodule.data.collect.collectorTargetGate
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
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
    private val http=OkHttpClient.Builder().connectTimeout(5,TimeUnit.SECONDS).readTimeout(10,TimeUnit.SECONDS).callTimeout(15,TimeUnit.SECONDS).build()
    private val names=AppNameResolver()
    private var loop: Job?=null
    private var processSampled=false
    @Volatile private var appContext: Context?=null
    private val uploads=UsageUploadGate { appContext?.let { enabled(it) && hasPermission(it) } == true }
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
            fun saveGap(reason: String, suspended: Boolean) {
                val r=UsageSessionEngine.gap(original,now,reason)
                store.save(r.copy(state=r.state.copy(suspended=suspended,boot=boot,elapsed=elapsed,observedAt=now)),targets)
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
            val result=UsageSessionEngine.reduce(state,events,until)
            records.addAll(result.records)
            store.save(result.copy(state=result.state.copy(boot=boot,elapsed=elapsed,observedAt=now),records=records.map { r ->
                r.copy(appName=r.packageName?.let { names.resolve(context,it) })
            }),targets)
            store.prune(now-7*DAY)
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
        val j=JSONObject(body); j.optBoolean("ok") && j.optInt("received",-1)==received
    } catch (_: Exception) { false }

    suspend fun sync(context: Context) = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            appContext=context.applicationContext
            if(!enabled(context)) return@withLock
            sample(context)
            if(!hasPermission(context)) return@withLock
            ServerConfig.init(context)
            val gate=collectorTargetGate(context,ServerConfig.baseUrl)
            UsageStore(context).use { store ->
                for(target in store.targets()) {
                    currentCoroutineContext().ensureActive()
                    if(!enabled(context) || !hasPermission(context)) break
                    if(!gate.canUpload(target)) continue
                    val batch=store.pending(target)
                    if(batch.isEmpty()) continue
                    try {
                        if (uploadUsageBatch(http,target,DataCollector.deviceId(context),batch,uploads)) {
                            store.acknowledge(target,batch.map { it.id })
                        }
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
    val payload=JSONObject().put("records",JSONArray().apply { batch.forEach { put(it.toJson()) } }).toString()
    val request=Request.Builder().url(target.trimEnd('/')+"/api/v1/mobile/app-usage/batch")
        .header("X-Device-Id",deviceId).post(payload.toRequestBody("application/json".toMediaType())).build()
    val call=if(gate!=null) gate.prepare(http,request) ?: return false else http.newCall(request)
    try {
        return call.execute().use { response ->
            response.isSuccessful && AppUsageTracker.acceptsReceipt(response.body?.string().orEmpty(),batch.size)
        }
    } finally { gate?.finish(call) }
}
