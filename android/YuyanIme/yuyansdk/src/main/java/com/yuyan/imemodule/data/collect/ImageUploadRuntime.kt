package com.yuyan.imemodule.data.collect

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import android.os.PowerManager
import android.os.Build
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

/** 截图准备避让输入；所有截图传输只允许线上、Wi-Fi、熄屏，避免争用其他应用。 */
object ImageUploadRuntime {
    private val schedule = ImageUploadSchedule(SystemClock::elapsedRealtime)
    private val cancellations = InputPriorityCancellation<Call>(Dispatchers.IO.asExecutor()) { it.cancel() }
    @Volatile private var observing = false

    fun isInputIdle(): Boolean = schedule.isInputIdle()
    internal fun isDeviceIdle(context: Context): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive == false
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

    fun beginPreparation(): Closeable? = schedule.beginPreparation()
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
        target.trimEnd('/') == ServerConfig.baseUrl && CollectionConsent.enabled(context) && isInputIdle() && wifi(context) != null

    fun canUploadScreenshot(context: Context, target: String): Boolean = canUploadChat(context, target) && isDeviceIdle(context)

    fun maxImageBytes(context: Context, target: String): Long =
        if (canUploadScreenshot(context,target)) schedule.maxImageBytes(ImageUploadNetwork.WIFI) else 0L

    fun tryStartImage(context: Context, target: String, bytes: Long): Closeable? =
        if (canUploadScreenshot(context,target)) schedule.tryStartImage(ImageUploadNetwork.WIFI,bytes) else null

    // Called from an IO worker, never from a key callback. Bound sockets/DNS cannot fall back to cellular.
    internal fun prepareChatCall(context: Context, target: String, http: OkHttpClient, request: Request, allowed: () -> Boolean = { true }): Call? {
        observe(context)
        val token=cancellations.token()
        // 只有无图片字节的聊天元数据沿用原时机；其他调用默认按截图保护。
        val screenshot = request.url.encodedPath != "/api/v1/mobile/chat/messages/batch"
        val ready = { allowed() && canUploadChat(context, target) && (!screenshot || isDeviceIdle(context)) }
        if (!ready()) return null
        val network=wifi(context) ?: return null
        val body=request.body ?: return null
        val client=http.newBuilder().socketFactory(network.socketFactory)
            .dns(object : okhttp3.Dns { override fun lookup(hostname: String) = network.getAllByName(hostname).toList() })
            .connectionPool(ConnectionPool(0,1,TimeUnit.SECONDS))
            .callTimeout(90,TimeUnit.SECONDS).build()
        val guarded=GuardedChatBody(body,allowed={
            ready() && cancellations.token()==token && wifi(context)==network
        })
        val call=client.newCall(request.newBuilder().method(request.method,guarded).build())
        cancellations.track(call, token)
        if (!ready() || cancellations.token()!=token || wifi(context)!=network) {
            call.cancel();cancellations.finish(call);return null
        }
        return call
    }

    internal fun finishChatCall(call: Call) { cancellations.finish(call) }

    @Synchronized private fun observe(context: Context) {
        if (observing) return
        val manager=context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        try {
            manager.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                object : ConnectivityManager.NetworkCallback() {
                    override fun onLost(network: Network) { cancelUploads() }
                    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                        if (wifi(context)==null) cancelUploads()
                    }
                })
            observing=true
            // 广播触发主动取消；逐块门禁仍复核屏幕状态，不能只依赖广播及时送达。
            val app = context.applicationContext
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == Intent.ACTION_SCREEN_ON) cancelUploads()
                    else if (intent.action == Intent.ACTION_SCREEN_OFF) DataCollector.requestSync()
                }
            }
            val filter = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) }
            runCatching {
                if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                else { @Suppress("DEPRECATION") app.registerReceiver(receiver, filter) }
            }
        } catch (_: Exception) { /* Per-chunk checks and Wi-Fi-bound sockets still fail closed. */ }
    }
}
