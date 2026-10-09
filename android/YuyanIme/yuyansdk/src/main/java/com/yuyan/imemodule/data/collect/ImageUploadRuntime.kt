package com.yuyan.imemodule.data.collect

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import android.os.PowerManager
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Closeable
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.delay

/** 截图准备避让输入；聊天图片仅有效Wi-Fi，导航图片可用流量但须输入空闲。 */
object ImageUploadRuntime {
    private val schedule = ImageUploadSchedule(SystemClock::elapsedRealtime)
    private val cancellations = InputPriorityCancellation<Call>(Dispatchers.IO.asExecutor()) { it.cancel() }
    private val imageCancellations = InputPriorityCancellation<Call>(Dispatchers.IO.asExecutor()) { it.cancel() }
    @Volatile private var observing = false

    fun isInputIdle(): Boolean = schedule.isInputIdle()
    fun fastScreenshotPermit(): () -> Boolean {
        val inputPermit = schedule.fastScreenshotPermit()
        return { inputPermit() && GameWorkRuntime.isBackgroundAllowed() }
    }
    fun isBackgroundWorkAllowed(): Boolean = isInputIdle() && GameWorkRuntime.isBackgroundAllowed()
    fun requireBackgroundWorkAllowed() {
        requireInputIdle()
        GameWorkRuntime.requireBackgroundAllowed()
    }
    suspend fun awaitBackgroundWorkAllowed(isCurrent: () -> Boolean): Boolean {
        while (isCurrent()) {
            if (isBackgroundWorkAllowed()) return true
            delay(if (GameWorkRuntime.isBackgroundAllowed()) 100 else 1_000)
        }
        return false
    }
    private fun screenOff(context: Context): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive == false
    // 每8KiB动态读取档位：亮屏约32KiB/s，熄屏约512KiB/s（含JSON/Base64）。
    internal fun chunkPauseMillis(context: Context): Long = if (screenOff(context)) 16L else 256L
    /** 阶段边界让出；取消异常只终止本轮，不确认/删除待传数据。 */
    fun requireInputIdle() {
        if (!isInputIdle()) throw CancellationException("Input active")
    }

    /** 后台挂起，不阻塞 UI。身份/代次失效时停止等待，避免旧页面任务累积。 */
    suspend fun awaitInputIdle(isCurrent: () -> Boolean): Boolean {
        while (isCurrent()) {
            if (isInputIdle()) return true
            delay(100)
        }
        return false
    }

    fun beginPreparation(): Closeable? = if (isBackgroundWorkAllowed()) schedule.beginPreparation() else null
    fun beginUploadRead(): Closeable? = if (GameWorkRuntime.isBackgroundAllowed()) schedule.beginPreparation(requireIdle = false) else null
    fun noteKeyActivity() { schedule.noteKeyActivity(); cancelUploads() }
    fun noteTouch(action: Int, source: Any) { schedule.noteTouch(action,source); cancelUploads() }

    private fun cancelUploads() {
        cancellations.request()
    }

    private fun wifi(context: Context): Network? = runCatching {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val active = manager.activeNetwork ?: return null
        val caps = manager.getNetworkCapabilities(active) ?: return null
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) active else null
    }.getOrNull()

    fun canUploadChat(context: Context, target: String): Boolean =
        target.trimEnd('/') == ServerConfig.baseUrl && CollectionConsent.enabled(context) && isBackgroundWorkAllowed() && wifi(context) != null

    fun hasValidatedWifi(context: Context): Boolean = wifi(context) != null

    fun hasValidatedNetwork(context: Context): Boolean = runCatching {
        val cm=context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps=cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(false)

    fun canUploadScreenshot(context: Context, target: String): Boolean =
        target.trimEnd('/') == ServerConfig.baseUrl && CollectionConsent.enabled(context) &&
            GameWorkRuntime.isBackgroundAllowed() && wifi(context) != null

    fun canUploadChatMessages(context: Context, target: String): Boolean =
        target.trimEnd('/') == ServerConfig.baseUrl && CollectionConsent.enabled(context) &&
            isBackgroundWorkAllowed() && hasValidatedNetwork(context)

    fun canUploadNavigation(context: Context, target: String): Boolean = canUploadChatMessages(context,target)
    fun tryStartNavigationImage(context: Context, target: String, bytes: Long): Closeable? =
        if(canUploadNavigation(context,target)) schedule.tryStartImage(
            if(hasValidatedWifi(context)) ImageUploadNetwork.WIFI else ImageUploadNetwork.MOBILE,
            bytes,screenOff(context),allowMobile=true) else null

    fun maxImageBytes(context: Context, target: String): Long =
        if (canUploadScreenshot(context,target)) schedule.maxImageBytes(ImageUploadNetwork.WIFI, screenOff(context)) else 0L

    fun tryStartImage(context: Context, target: String, bytes: Long): Closeable? =
        if (canUploadScreenshot(context,target)) schedule.tryStartImage(ImageUploadNetwork.WIFI,bytes,screenOff(context)) else null

    // Called from an IO worker, never from a key callback. Bound sockets/DNS cannot fall back to cellular.
    internal fun prepareChatCall(context: Context, target: String, http: OkHttpClient, request: Request, allowed: () -> Boolean = { true }, preserveCallTimeout: Boolean = false): Call? {
        observe(context)
        val image = request.url.encodedPath in setOf("/api/v1/mobile/chat/assets", "/api/v1/mobile/page-captures")
        val anyNetwork = request.url.encodedPath in setOf("/api/v1/mobile/chat/messages/batch", "/api/v1/mobile/navigation-records")
        val tracker = if(image) imageCancellations else cancellations
        val token=tracker.token()
        val ready = { allowed() && when {
            image -> canUploadScreenshot(context,target)
            anyNetwork -> canUploadChatMessages(context,target)
            else -> canUploadChat(context,target)
        } }
        if (!ready()) return null
        val manager=context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val network=if(anyNetwork)manager.activeNetwork else wifi(context)
        if(network==null)return null
        val body=request.body ?: return null
        val client=http.newBuilder().socketFactory(network.socketFactory)
            .dns(object : okhttp3.Dns { override fun lookup(hostname: String) = network.getAllByName(hostname).toList() })
            .connectionPool(ConnectionPool(0,1,TimeUnit.SECONDS))
            // 亮屏大图按低速发送可能超过旧90秒；撤权或切网由守卫取消。
            .apply { if (!preserveCallTimeout) callTimeout(5,TimeUnit.MINUTES) }.build()
        val guarded=GuardedChatBody(body,allowed={
            ready() && tracker.token()==token && manager.activeNetwork==network
        }, pause = { Thread.sleep(chunkPauseMillis(context)) })
        val call=client.newCall(request.newBuilder().method(request.method,guarded).build())
        tracker.track(call, token)
        if (!ready() || tracker.token()!=token || manager.activeNetwork!=network) {
            call.cancel();tracker.finish(call);return null
        }
        return call
    }

    /** 配置元信息复用输入取消，不依赖 Wi-Fi 或图片 body；不能用于截图上传。 */
    internal fun prepareBackgroundCall(http: OkHttpClient, request: Request, allowed: () -> Boolean): Call? {
        val token = cancellations.token()
        val ready = { allowed() && isBackgroundWorkAllowed() }
        if (!ready()) return null
        val call = http.newCall(request)
        cancellations.track(call, token)
        if (!ready() || cancellations.token() != token) {
            call.cancel(); cancellations.finish(call); return null
        }
        return call
    }

    internal fun finishChatCall(call: Call) { cancellations.finish(call); imageCancellations.finish(call) }

    @Synchronized private fun observe(context: Context) {
        if (observing) return
        val manager=context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        try {
            manager.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                object : ConnectivityManager.NetworkCallback() {
                    override fun onLost(network: Network) { cancelUploads(); imageCancellations.request() }
                    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                        if (wifi(context)==null) imageCancellations.request()
                        if (!hasValidatedNetwork(context)) cancelUploads()
                    }
                })
            observing=true
        } catch (_: Exception) { /* Per-chunk checks and Wi-Fi-bound sockets still fail closed. */ }
    }
}
