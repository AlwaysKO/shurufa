package com.yuyan.imemodule.data.collect

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import java.io.Closeable

/** Runtime bridge shared by collection preparation and both destination upload paths. */
object ImageUploadRuntime {
    private val schedule = ImageUploadSchedule(SystemClock::elapsedRealtime)

    fun isInputIdle(): Boolean = schedule.isInputIdle()
    fun beginPreparation(): Closeable? = schedule.beginPreparation()
    fun noteKeyActivity() = schedule.noteKeyActivity()
    fun noteTouch(action: Int, source: Any) = schedule.noteTouch(action, source)

    fun maxImageBytes(context: Context, target: String): Long =
        schedule.maxImageBytes(network(context, target))

    fun tryStartImage(context: Context, target: String, bytes: Long): Closeable? =
        schedule.tryStartImage(network(context, target), bytes)

    private fun network(context: Context, target: String): ImageUploadNetwork {
        // The caller still verifies reverse forwarding health; USB needs no Internet.
        if (ImageUploadSchedule.isUsbTarget(target)) return ImageUploadNetwork.USB
        return runCatching {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return ImageUploadNetwork.OFFLINE
            val active = manager.activeNetwork ?: return ImageUploadNetwork.OFFLINE
            val capabilities = manager.getNetworkCapabilities(active) ?: return ImageUploadNetwork.OFFLINE
            if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                ImageUploadNetwork.OFFLINE
            } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                ImageUploadNetwork.WIFI
            } else {
                // VPN, Ethernet and unknown transports never silently bypass the mobile budget.
                ImageUploadNetwork.MOBILE
            }
        }.getOrDefault(ImageUploadNetwork.OFFLINE)
    }
}
