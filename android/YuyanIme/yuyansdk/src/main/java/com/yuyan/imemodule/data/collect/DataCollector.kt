package com.yuyan.imemodule.data.collect

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Network
import android.net.NetworkRequest
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.yuyan.imemodule.data.emojicon.YuyanEmojiCompat
import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.data.completion.CompletionSync
import com.yuyan.imemodule.data.phrase.PhraseSync
import com.yuyan.imemodule.data.sticker.StickerSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 数据采集器：设备注册、行为事件批量上报、位置采集（每分钟，服务端去重）。
 * 明确授权后先持久化到手机，再独立补传电脑和线上；总开关与位置开关均可暂停上报。
 */
object DataCollector {

    private const val TAG = "ShurufaCollector"
    private const val KEY_DEVICE_UUID = "collector_device_uuid"
    private const val KEY_LOCATION_ENABLE = "location_tracking_enable"
    private const val KEY_LAST_LOCATION_LATITUDE = "collector_last_location_latitude"
    private const val KEY_LAST_LOCATION_LONGITUDE = "collector_last_location_longitude"
    private const val KEY_LAST_LOCATION_ACCURACY = "collector_last_location_accuracy"
    private const val KEY_LAST_LOCATION_TIME = "collector_last_location_time"
    private const val KEY_LAST_LOCATION_UPLOADED_AT = "collector_last_location_uploaded_at"
    private const val FLUSH_INTERVAL_MS = 30_000L
    private const val LOCATION_INTERVAL_MS = 60_000L
    private const val LOCATION_MIN_DISTANCE_M = 10f
    private const val LOCAL_CHAT_RETENTION_MS = 7L * 24 * 60 * 60 * 1000
    private const val ONLINE_CONFIG_REFRESH_MS = 5L * 60 * 1000

    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    private val appNames = AppNameResolver()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var eventStore: LocalInputStore? = null
    @Volatile private var delivery: EventDelivery? = null
    private val dictionarySyncs = java.util.concurrent.ConcurrentHashMap<DictionarySyncTarget, PersonalDictionarySync>()
    @Volatile private var appContext: Context? = null
    private var networkRegistered = false
    private val deliveryTasks = TargetDeliveryTasks()
    private val lastAttempt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var wakeJob: Job? = null

    @Synchronized private fun store(context: Context): LocalInputStore =
        eventStore ?: LocalInputStore(context).also { eventStore = it }
    private val locationUploadMutex = Mutex()
    private val onlineConfigMutex = Mutex()
    @Volatile private var lastOnlineConfigRefreshElapsed = 0L
    private val passiveRegistrationGate = LocationRegistrationGate()
    private val activeRegistrationGate = LocationRegistrationGate()
    // SimpleDateFormat 非线程安全（IME 主线程 + IO 协程并发调用），用 ThreadLocal 隔离
    private val iso8601 = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US) }

    @Volatile
    private var prefs: SharedPreferences? = null
    @Volatile
    private var currentDeviceId: String? = null
    @Volatile
    private var flushJob: Job? = null
    @Volatile
    private var locationJob: Job? = null
    @Volatile
    private var locationManager: LocationManager? = null
    @Volatile
    private var passiveLocationListener: LocationListener? = null
    @Volatile
    private var activeLocationListener: LocationListener? = null
    @Volatile
    private var inputActive = false

    // ---------- 初始化 ----------

    @Synchronized fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        prefs = PreferenceManager.getDefaultSharedPreferences(app)
        currentDeviceId = deviceId(app)
        ServerConfig.init(app)
        if (CollectionConsent.enabled(app)) {
            CompletionSync.init(app)
            PhraseSync.init(app)
        }
        StickerSync.init(app) // 表情包请求按当前设备隔离
        if (delivery == null) registerDevice(app)
        if (CollectionConsent.enabled(app)) ReportSyncJobService.schedule(app)
        registerNetworkWake(app)
        if (flushJob == null) {
            flushJob = scope.launch {
                while (true) {
                    // 积压期间小步检查，停打后及时恢复；空队列仍沿用30秒周期。
                    delay(if (CollectionConsent.enabled(app) && eventStore?.hasPendingImages() == true) 3_000L else FLUSH_INTERVAL_MS)
                    flushEvents()
                }
            }
        }
        ensureLocationUpdates(app)
    }

    /** 设备 UUID：首次生成后持久化 */
    fun deviceId(context: Context): String = InstallDeviceIdentity.resolve(
        PreferenceManager.getDefaultSharedPreferences(context), KEY_DEVICE_UUID,
        java.io.File(context.noBackupFilesDir, "collector_device_identity"),
    )

    val locationTrackingEnabled: Boolean
        get() = appContext?.let { CollectionConsent.enabled(it) } == true && (prefs?.getBoolean(KEY_LOCATION_ENABLE, true) ?: true)

    /** 设置页开关联动：关闭时立即停止定位监听，重新开启时恢复 */
    fun setLocationTrackingEnabled(context: Context, enabled: Boolean) {
        val sp = prefs ?: PreferenceManager.getDefaultSharedPreferences(context).also { prefs = it }
        sp.edit().putBoolean(KEY_LOCATION_ENABLE, enabled).apply()
        if (enabled) {
            ensureLocationUpdates(context.applicationContext)
        } else {
            locationJob?.cancel()
            locationJob = null
            stopLocationUpdates()
        }
    }

    /** 输入法活跃时主动定位；非活跃时仅保留被动定位。 */
    fun setInputActive(context: Context, active: Boolean) {
        inputActive = active
        if (prefs == null || !locationTrackingEnabled) return
        if (active) {
            ensureLocationUpdates(context.applicationContext)
            locationManager?.let { registerActiveLocationUpdates(context.applicationContext, it) }
        } else {
            stopActiveLocationUpdates()
        }
    }

    // ---------- 设备注册 ----------

    private fun registerDevice(context: Context) {
        val info = DeviceInfo(
            id = deviceId(context),
            name = "我的手机",
            platform = "android",
            model = Build.MODEL,
            osVersion = Build.VERSION.RELEASE,
            appVersion = appVersionName(context),
            brand = Build.BRAND,
            sdkInt = Build.VERSION.SDK_INT,
            screenResolution = screenResolution(context),
            locale = Locale.getDefault().toLanguageTag(),
            region = Locale.getDefault().country,
            hardware = Build.HARDWARE,
            romVersion = Build.DISPLAY,
            ramMb = (totalMem() / 1024 / 1024).toInt(),
        )
        delivery = EventDelivery(
            store = store(context),
            http = http,
            deviceId = info.id,
            deviceJson = json.encodeToString(DeviceInfo.serializer(), info),
            onlineTarget = { ServerConfig.baseUrl },
            allowed = { kind -> CollectionConsent.enabled(context) && (kind != "location" || locationTrackingEnabled) },
            maxImageBytes = { target -> ImageUploadRuntime.maxImageBytes(context, target) },
            tryStartImage = { target, bytes -> ImageUploadRuntime.tryStartImage(context, target, bytes) },
        )
        requestSync()
    }

    // ---------- 事件上报 ----------

    /** 记录一次行为事件（SQLite 持久队列，重试复用同一 id，两个目标分别确认） */
    fun recordEvent(
        context: Context,
        eventType: String,
        text: String? = null,
        packageName: String? = null,
        editorId: String? = null,
        inputCode: String? = null,
        source: String? = null,
        sessionId: String? = null,
        sequenceNo: Long? = null,
        textBefore: String? = null,
        textAfter: String? = null,
        metadata: JsonObject? = null,
    ): Boolean {
        if (!CollectionConsent.enabled(context) || !CollectionConsent.allowsEditor(YuyanEmojiCompat.mEditorInfo) || !CollectionConsent.allowsText(text) || !CollectionConsent.allowsText(textBefore) || !CollectionConsent.allowsText(textAfter)) return false
        val event = MobileEvent(
            id = UUID.randomUUID().toString(),
            deviceId = deviceId(context),
            eventType = eventType,
            text = text?.take(5000),
            packageName = packageName,
            appName = appNames.resolve(context, packageName),
            editorId = editorId,
            sequenceNo = sequenceNo ?: System.currentTimeMillis(),
            sessionId = sessionId,
            textBefore = textBefore,
            textAfter = textAfter,
            metadata = metadata,
            inputCode = inputCode,
            networkType = networkType(context),
            source = source,
            occurredAt = iso8601.get().format(Date()),
        )
        try {
            store(context).enqueue(event, ServerConfig.eventTargets)
            requestSync()
            return true
        } catch (error: Exception) {
            Log.e(TAG, "事件落盘失败，未视为已上报", error)
            return false
        }
    }

    private fun flushEvents() { scope.launch { flushNow() } }

    suspend fun flushNow() = coroutineScope {
        val uploader = delivery ?: return@coroutineScope
        val app = appContext ?: return@coroutineScope
        if (!CollectionConsent.enabled(app)) {
            eventStore?.pruneExpiredLocalChatReports(ServerConfig.baseUrl, LOCAL_CHAT_RETENTION_MS)
            return@coroutineScope
        }
        refreshOnlineServerUrl(app)
        val onlineTarget = ServerConfig.baseUrl
        eventStore?.let {
            it.pruneExpiredLocalChatReports(onlineTarget, LOCAL_CHAT_RETENTION_MS)
        }
        val targets = (ServerConfig.eventTargets + eventStore?.targets().orEmpty() + eventStore?.reportTargets().orEmpty()).distinct()
        val targetGate = collectorTargetGate(app, onlineTarget)
        deliveryTasks.run(
            targets,
            onBusy = { ReportingTrace.record(ReportingStage.BUSY, it == onlineTarget) },
            onFailure = { target, _ ->
                lastAttempt[target] = android.os.SystemClock.elapsedRealtime() + FLUSH_INTERVAL_MS
                ReportingTrace.record(ReportingStage.DELIVERY_ERROR, target == onlineTarget)
            },
        ) { target ->
            if (!targetGate.canUpload(target)) return@run
            val now = android.os.SystemClock.elapsedRealtime()
            if (now < (lastAttempt[target] ?: 0L)) {
                ReportingTrace.record(ReportingStage.BACKOFF, target == onlineTarget)
                return@run
            }
            ReportingTrace.record(ReportingStage.FLUSH_START, target == onlineTarget)
            val taskContext = currentCoroutineContext()
            val ok = uploader.drain(target,
                beforeBatch = { taskContext.ensureActive() },
                beforeRequest = { taskContext.ensureActive() })
            ReportingTrace.record(ReportingStage.FLUSH_END, target == onlineTarget, flag = ok)
            val plan = dictionarySyncTargets(ServerConfig.eventTargets, ServerConfig.baseUrl, ServerConfig.dictionaryAuthorityUrl)
                .firstOrNull { it.url == target }
            if (plan != null && CollectionConsent.enabled(app)) {
                val sync = dictionarySyncs.getOrPut(plan) {
                    PersonalDictionarySync(store(app), app.getSharedPreferences("personal_dictionary_sync_v1", 0), http,
                        deviceId(app), plan.url, { CollectionConsent.enabled(app) }, {
                            val migration = app.getSharedPreferences("system_dictionary_migration_v1", 0)
                            migration.getString("status", "not_attempted")!! to migration.getInt("imported", 0)
                        }, restoreFromTarget = plan.restoreFromTarget, statePrefix = plan.statePrefix)
                }
                if (!sync.run()) Log.w(TAG, "个人词库尚未同步确认，保留本机记录（目标：$target）")
            }
            lastAttempt[target] = android.os.SystemClock.elapsedRealtime() + if (ok) 5_000 else FLUSH_INTERVAL_MS
            if (!ok) Log.w(TAG, "同步未确认，保留手机待传数据")
        }
    }

    private suspend fun refreshOnlineServerUrl(context: Context) = onlineConfigMutex.withLock {
        val elapsed = android.os.SystemClock.elapsedRealtime()
        if (lastOnlineConfigRefreshElapsed != 0L &&
            elapsed - lastOnlineConfigRefreshElapsed in 0 until ONLINE_CONFIG_REFRESH_MS
        ) return@withLock
        lastOnlineConfigRefreshElapsed = elapsed
        val current = ServerConfig.baseUrl
        val discovered = withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$current/api/v1/mobile/config")
                    .header("X-Device-Id", deviceId(context))
                    .get()
                    .build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) null else json.parseToJsonElement(response.body?.string().orEmpty())
                        .jsonObject["collector_base_url"]?.jsonPrimitive?.contentOrNull
                }
            } catch (_: Exception) { null }
        } ?: return@withLock
        val (oldTarget, newTarget) = ServerConfig.updateOnlineServerUrl(discovered) ?: return@withLock
        store(context).replaceTarget(oldTarget, newTarget)
        Log.i(TAG, "线上同步目标已按后台配置更新")
    }

    /** Durable before returning; callers never wait for network. */
    fun enqueueReport(context: Context, kind: String, payload: String): Boolean {
        if (!CollectionConsent.enabled(context)) return false
        if (kind == "location" && !locationTrackingEnabled) return false
        if (kind in listOf("completion_feedback", "phrase_use", "sticker_use") && !CollectionConsent.allowsEditor(YuyanEmojiCompat.mEditorInfo)) return false
        return try {
            val safePayload = if (kind == "chat_messages") filterChatReportPayload(payload) else payload
            val body = json.parseToJsonElement(safePayload).jsonObject
            if (kind == "chat_messages" && body.getValue("messages").jsonArray.isEmpty()) return true // 只丢弃明确过滤的敏感消息
            val fields = when(kind) {
                "completion_feedback" -> listOf("prefix", "completion")
                "phrase_upsert", "phrase_use" -> listOf("content")
                else -> emptyList()
            }
            if (fields.any { !CollectionConsent.allowsText(body[it]?.jsonPrimitive?.contentOrNull) }) return false
            ServerConfig.init(context)
            store(context).enqueueReport(PendingReport(UUID.randomUUID().toString(), kind, safePayload), ServerConfig.eventTargets)
            requestSync()
            true
        } catch (_: Exception) { Log.e(TAG, "报告落盘失败，保留原数据重试"); false }
    }

    fun enqueueReport(kind: String, payload: String): Boolean = appContext?.let { enqueueReport(it, kind, payload) } ?: false

    fun enqueueRawReport(context: Context, path: String, payload: String): Boolean {
        val kind = when (path) {
            "/api/v1/mobile/chat/assets" -> "chat_asset"
            "/api/v1/mobile/chat/messages/batch" -> "chat_messages"
            else -> return false
        }
        return enqueueReport(context, kind, payload)
    }

    @Synchronized fun requestSync() {
        if (wakeJob?.isActive == true) return
        wakeJob = scope.launch { delay(5_000); flushEvents() }
    }

    fun setCollectionEnabled(context: Context, enabled: Boolean) {
        CollectionConsent.setEnabled(context, enabled)
        if (enabled) {
            init(context)
            ReportSyncJobService.schedule(context)
            ensureLocationUpdates(context.applicationContext)
            requestSync()
        } else {
            locationJob?.cancel(); locationJob = null
            stopLocationUpdates()
            http.dispatcher.cancelAll()
            ReportSyncJobService.cancel(context)
        }
    }

    fun cancelTransfers() { http.dispatcher.cancelAll() }

    private fun registerNetworkWake(context: Context) {
        if (networkRegistered) return
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.registerNetworkCallback(NetworkRequest.Builder().build(), object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { requestSync() }
            })
            networkRegistered = true
        } catch (_: Exception) { /* 周期任务仍可重试 */ }
    }

    // ---------- 位置采集 ----------

    private fun ensureLocationUpdates(context: Context) {
        val current = locationJob
        if (current == null || current.isCompleted) {
            locationJob = scope.launch { startLocationUpdates(context) }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun startLocationUpdates(context: Context) {
        // 等待权限就绪（ImeService 请求授权后回调 enableLocationTracking）
        var waited = 0
        while (!hasLocationPermission(context) && waited < 60_000) {
            delay(2_000)
            waited += 2_000
        }
        if (!hasLocationPermission(context) || !locationTrackingEnabled) return

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locationManager = lm
        registerPassiveLocationUpdates(context, lm)
        if (inputActive) registerActiveLocationUpdates(context, lm)
        // 启动时先补一次最后已知位置
        val best = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { lm.getLastKnownLocation(it) }
            .maxByOrNull { it.time }
        if (best != null) reportLocation(context, best)
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    private fun registerPassiveLocationUpdates(context: Context, lm: LocationManager) {
        if (passiveLocationListener != null || !hasLocationPermission(context) || !passiveRegistrationGate.tryStart()) return
        val listener = LocationListener { loc -> reportLocation(context, loc) }
        try {
            lm.requestLocationUpdates(
                LocationManager.PASSIVE_PROVIDER,
                LOCATION_INTERVAL_MS,
                0f,
                listener,
                Looper.getMainLooper(),
            )
            passiveLocationListener = listener
        } catch (_: Exception) {
            passiveRegistrationGate.reset()
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    private fun registerActiveLocationUpdates(context: Context, lm: LocationManager) {
        if (activeLocationListener != null || !inputActive || !hasLocationPermission(context) || !activeRegistrationGate.tryStart()) return
        val listener = LocationListener { loc -> reportLocation(context, loc) }
        var registered = false
        try {
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                LOCATION_INTERVAL_MS,
                LOCATION_MIN_DISTANCE_M,
                listener,
                Looper.getMainLooper(),
            )
            registered = true
        } catch (_: Exception) { /* GPS 不可用时忽略 */ }
        try {
            lm.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                LOCATION_INTERVAL_MS,
                LOCATION_MIN_DISTANCE_M,
                listener,
                Looper.getMainLooper(),
            )
            registered = true
        } catch (_: Exception) { /* 网络定位不可用时忽略 */ }
        if (registered && inputActive) {
            activeLocationListener = listener
        } else {
            if (registered) lm.removeUpdates(listener)
            activeRegistrationGate.reset()
        }
    }

    @Synchronized
    private fun stopActiveLocationUpdates() {
        activeLocationListener?.let { listener ->
            try {
                locationManager?.removeUpdates(listener)
            } catch (_: Exception) { /* 已注销时忽略 */ }
        }
        activeLocationListener = null
        activeRegistrationGate.reset()
    }

    @Synchronized
    private fun stopLocationUpdates() {
        stopActiveLocationUpdates()
        passiveLocationListener?.let { listener ->
            try {
                locationManager?.removeUpdates(listener)
            } catch (_: Exception) { /* 已注销时忽略 */ }
        }
        passiveLocationListener = null
        passiveRegistrationGate.reset()
        locationManager = null
    }

    private fun reportLocation(context: Context, loc: Location) {
        if (!locationTrackingEnabled) return  // 开关关闭后不再上报（双保险）
        val candidate = LocationCandidate(
            latitude = loc.latitude,
            longitude = loc.longitude,
            accuracyMeters = if (loc.hasAccuracy()) loc.accuracy else Float.POSITIVE_INFINITY,
            locationTimeMs = loc.time,
        )
        scope.launch {
            locationUploadMutex.withLock {
                val nowMs = System.currentTimeMillis()
                if (!LocationUploadPolicy.shouldUpload(nowMs, candidate, readLastUploadedLocation())) return@withLock
                val report = LocationReport(
                    deviceId = deviceId(context),
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    accuracy = if (loc.hasAccuracy()) loc.accuracy else null,
                    provider = loc.provider,
                    speed = if (loc.hasSpeed()) loc.speed else null,
                    occurredAt = iso8601.get().format(Date(loc.time)),
                )
                if (enqueueReport(context, "location", json.encodeToString(LocationReport.serializer(), report))) {
                    // 节流以成功落盘为界，不以网络成功为界；断网期间仍保存移动轨迹。
                    saveLastUploadedLocation(candidate, nowMs)
                }
            }
        }
    }

    private fun readLastUploadedLocation(): UploadedLocation? {
        val sp = prefs ?: return null
        if (!sp.contains(KEY_LAST_LOCATION_UPLOADED_AT)) return null
        val latitude = sp.getString(KEY_LAST_LOCATION_LATITUDE, null)?.toDoubleOrNull() ?: return null
        val longitude = sp.getString(KEY_LAST_LOCATION_LONGITUDE, null)?.toDoubleOrNull() ?: return null
        return UploadedLocation(
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = sp.getFloat(KEY_LAST_LOCATION_ACCURACY, Float.POSITIVE_INFINITY),
            locationTimeMs = sp.getLong(KEY_LAST_LOCATION_TIME, 0L),
            uploadedAtMs = sp.getLong(KEY_LAST_LOCATION_UPLOADED_AT, 0L),
        )
    }

    private fun saveLastUploadedLocation(candidate: LocationCandidate, uploadedAtMs: Long) {
        prefs?.edit()
            ?.putString(KEY_LAST_LOCATION_LATITUDE, candidate.latitude.toString())
            ?.putString(KEY_LAST_LOCATION_LONGITUDE, candidate.longitude.toString())
            ?.putFloat(KEY_LAST_LOCATION_ACCURACY, candidate.accuracyMeters)
            ?.putLong(KEY_LAST_LOCATION_TIME, candidate.locationTimeMs)
            ?.putLong(KEY_LAST_LOCATION_UPLOADED_AT, uploadedAtMs)
            ?.apply()
    }

    // ---------- 工具 ----------

    private fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun networkType(context: Context): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_NETWORK_STATE) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            when (cm.activeNetworkInfo?.type) {
                ConnectivityManager.TYPE_WIFI -> "wifi"
                ConnectivityManager.TYPE_ETHERNET -> "ethernet"
                ConnectivityManager.TYPE_MOBILE -> "mobile"
                else -> null
            }
        } catch (_: SecurityException) {
            null
        }
    }

    private fun appVersionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
    } catch (_: Exception) {
        "1.0.0"
    }

    private fun screenResolution(context: Context): String {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val size = android.graphics.Point()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealSize(size)
        return "${size.x}x${size.y}"
    }

    private fun totalMem(): Long {
        val am = (appContext ?: return 0L).getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.totalMem
    }
}

// ---------- 协议 DTO（与服务端 /api/v1/mobile/* 对应，snake_case） ----------

@Serializable
internal data class DeviceInfo(
    val id: String,
    val name: String,
    val platform: String,
    @SerialName("os_version") val osVersion: String,
    @SerialName("app_version") val appVersion: String,
    val model: String,
    val brand: String,
    @SerialName("sdk_int") val sdkInt: Int,
    @SerialName("screen_resolution") val screenResolution: String,
    val locale: String,
    val region: String,
    val hardware: String,
    @SerialName("rom_version") val romVersion: String,
    @SerialName("ram_mb") val ramMb: Int,
)

@Serializable
internal data class MobileEvent(
    val id: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("event_type") val eventType: String,
    val text: String? = null,
    @SerialName("package_name") val packageName: String? = null,
    @SerialName("editor_id") val editorId: String? = null,
    @SerialName("sequence_no") val sequenceNo: Long = 0,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("text_before") val textBefore: String? = null,
    @SerialName("text_after") val textAfter: String? = null,
    val metadata: JsonObject? = null,
    @SerialName("input_code") val inputCode: String? = null,
    @SerialName("network_type") val networkType: String? = null,
    val source: String? = null,
    @SerialName("occurred_at") val occurredAt: String,
    @SerialName("app_name") val appName: String? = null,
)

@Serializable
internal data class EventBatch(
    @SerialName("device_id") val deviceId: String,
    val events: List<MobileEvent>,
)

@Serializable
internal data class LocationReport(
    @SerialName("device_id") val deviceId: String,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float? = null,
    val provider: String? = null,
    val speed: Float? = null,
    @SerialName("occurred_at") val occurredAt: String,
)
