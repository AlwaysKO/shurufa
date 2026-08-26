package com.yuyan.imemodule.data.collect

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/**
 * 数据采集器：设备注册、行为事件批量上报、位置采集（每分钟，服务端去重）。
 * 个人自用采集，数据仅存本地后端；位置采集可用 SharedPreferences key=location_tracking_enable 关闭（默认开）。
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
    private const val EVENT_BATCH_MAX = 500
    private const val FLUSH_INTERVAL_MS = 30_000L
    private const val LOCATION_INTERVAL_MS = 60_000L
    private const val LOCATION_MIN_DISTANCE_M = 10f

    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = ConcurrentLinkedQueue<MobileEvent>()
    private val locationUploadMutex = Mutex()
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

    fun init(context: Context) {
        val app = context.applicationContext
        prefs = PreferenceManager.getDefaultSharedPreferences(app)
        currentDeviceId = deviceId(app)
        ServerConfig.init(app)
        CompletionSync.init(app) // 服务端智能补全候选同步
        PhraseSync.init(app) // 常用语云同步
        StickerSync.init(app) // 表情包请求按当前设备隔离
        registerDevice(app)
        if (flushJob == null) {
            flushJob = scope.launch {
                while (true) {
                    delay(FLUSH_INTERVAL_MS)
                    flushEvents()
                }
            }
        }
        ensureLocationUpdates(app)
    }

    /** 设备 UUID：首次生成后持久化 */
    fun deviceId(context: Context): String {
        val sp = PreferenceManager.getDefaultSharedPreferences(context)
        sp.getString(KEY_DEVICE_UUID, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        sp.edit().putString(KEY_DEVICE_UUID, id).apply()
        return id
    }

    val locationTrackingEnabled: Boolean
        get() = prefs?.getBoolean(KEY_LOCATION_ENABLE, true) ?: true

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
        scope.launch {
            try {
                post("/api/v1/mobile/device", json.encodeToString(DeviceInfo.serializer(), info)).use { response ->
                    if (response.isSuccessful) {
                        Log.i(TAG, "设备注册上报成功 code=${response.code}")
                    } else {
                        Log.w(TAG, "设备注册上报失败 code=${response.code}")
                    }
                }
            } catch (error: Exception) {
                Log.w(TAG, "设备注册上报异常：${error.message}", error)
            }
        }
    }

    // ---------- 事件上报 ----------

    /** 记录一次行为事件（入内存队列，定时批量上报；重试复用同一 id，服务端幂等去重） */
    fun recordEvent(
        context: Context,
        eventType: String,
        text: String? = null,
        packageName: String? = null,
        editorId: String? = null,
        inputCode: String? = null,
        source: String? = null,
    ) {
        val event = MobileEvent(
            id = UUID.randomUUID().toString(),
            deviceId = deviceId(context),
            eventType = eventType,
            text = text?.take(5000),
            packageName = packageName,
            editorId = editorId,
            sequenceNo = System.currentTimeMillis(),
            inputCode = inputCode,
            networkType = networkType(context),
            source = source,
            occurredAt = iso8601.get().format(Date()),
        )
        queue.add(event)
        if (queue.size >= EVENT_BATCH_MAX) {
            // 队列积压过多时立即补一次 flush（不取消定时循环）
            scope.launch { flushEvents() }
        }
    }

    private fun flushEvents() {
        if (queue.isEmpty()) return
        val batch = mutableListOf<MobileEvent>()
        while (batch.size < EVENT_BATCH_MAX) {
            batch.add(queue.poll() ?: break)
        }
        if (batch.isEmpty()) return
        try {
            val body = json.encodeToString(EventBatch.serializer(), EventBatch(deviceId = batch.first().deviceId, events = batch))
            post("/api/v1/mobile/events/batch", body).use { response ->
                if (response.isSuccessful) {
                    Log.i(TAG, "事件批量上报成功 count=${batch.size} code=${response.code}")
                } else {
                    Log.w(TAG, "事件批量上报失败 count=${batch.size} code=${response.code}")
                    // 失败回队重试（幂等 id，重复上报服务端不重复入库）
                    batch.forEach { queue.add(it) }
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "事件批量上报异常 count=${batch.size}：${error.message}", error)
            batch.forEach { queue.add(it) }
        }
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
                try {
                    post("/api/v1/mobile/location", json.encodeToString(LocationReport.serializer(), report)).use { response ->
                        if (response.isSuccessful) {
                            saveLastUploadedLocation(candidate, nowMs)
                            Log.i(TAG, "位置上报成功 code=${response.code}")
                        } else {
                            Log.w(TAG, "位置上报失败 code=${response.code}")
                        }
                    }
                } catch (error: Exception) {
                    Log.w(TAG, "位置上报异常：${error.message}", error)
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

    private fun post(path: String, bodyJson: String): okhttp3.Response {
        val request = Request.Builder()
            .url(ServerConfig.baseUrl + path)
            .header("Content-Type", "application/json")
            .header("X-Device-Id", currentDeviceId ?: error("DataCollector is not initialized"))
            .post(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return http.newCall(request).execute()
    }

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
        val am = Launcher.instance.context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
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
    @SerialName("input_code") val inputCode: String? = null,
    @SerialName("network_type") val networkType: String? = null,
    val source: String? = null,
    @SerialName("occurred_at") val occurredAt: String,
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
