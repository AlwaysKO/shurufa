package com.yuyan.imemodule.data.collect

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
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

/** Screenshot preparation is idle-only; actual chat uploads are online + Wi-Fi only. */
object ImageUploadRuntime {
    private val schedule = ImageUploadSchedule(SystemClock::elapsedRealtime)
    private val cancellations = InputPriorityCancellation<Call>(Dispatchers.IO.asExecutor()) { it.cancel() }
    @Volatile private var observing = false

    fun isInputIdle(): Boolean = schedule.isInputIdle()
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

    fun maxImageBytes(context: Context, target: String): Long =
        if (canUploadChat(context,target)) schedule.maxImageBytes(ImageUploadNetwork.WIFI) else 0L

    fun tryStartImage(context: Context, target: String, bytes: Long): Closeable? =
        if (canUploadChat(context,target)) schedule.tryStartImage(ImageUploadNetwork.WIFI,bytes) else null

    // Called from an IO worker, never from a key callback. Bound sockets/DNS cannot fall back to cellular.
    internal fun prepareChatCall(context: Context, target: String, http: OkHttpClient, request: Request): Call? {
        observe(context)
        val token=cancellations.token()
        if (!canUploadChat(context,target)) return null
        val network=wifi(context) ?: return null
        val body=request.body ?: return null
        val client=http.newBuilder().socketFactory(network.socketFactory)
            .dns(object : okhttp3.Dns { override fun lookup(hostname: String) = network.getAllByName(hostname).toList() })
            .connectionPool(ConnectionPool(0,1,TimeUnit.SECONDS))
            .callTimeout(90,TimeUnit.SECONDS).build()
        val guarded=GuardedChatBody(body,allowed={
            cancellations.token()==token && canUploadChat(context,target) && wifi(context)==network
        })
        val call=client.newCall(request.newBuilder().method(request.method,guarded).build())
        cancellations.track(call, token)
        if (cancellations.token()!=token || !canUploadChat(context,target) || wifi(context)!=network) {
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
        } catch (_: Exception) { /* Per-chunk checks and Wi-Fi-bound sockets still fail closed. */ }
    }
}
