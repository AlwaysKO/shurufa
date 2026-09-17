package com.yuyan.imemodule.data.collect

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

internal class CollectorTargetGate(
    onlineTarget: String,
    private val usbConnected: () -> Boolean,
    private val localHealthy: (String) -> Boolean,
) {
    private val onlineTarget = onlineTarget.trimEnd('/')

    fun canUpload(target: String): Boolean {
        val normalized = target.trimEnd('/')
        if (normalized == onlineTarget) return true
        return usbConnected() && localHealthy(normalized)
    }
}

internal fun isUsbDataLink(connected: Boolean, configured: Boolean): Boolean = connected && configured

internal fun isUsbDataLink(context: Context): Boolean {
    val state = context.registerReceiver(null, IntentFilter("android.hardware.usb.action.USB_STATE"))
    val dataLink = state != null && isUsbDataLink(
        connected = state.getBooleanExtra("connected", false),
        configured = state.getBooleanExtra("configured", false),
    )
    val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val usbPowered = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) == BatteryManager.BATTERY_PLUGGED_USB
    return isPhysicalUsbConnected(dataLink, usbPowered)
}

internal fun isPhysicalUsbConnected(dataLink: Boolean, usbPowered: Boolean): Boolean = dataLink || usbPowered

internal fun localCollectorHealthy(http: OkHttpClient, target: String): Boolean = try {
    val request = Request.Builder().url(target.trimEnd('/') + "/health").get().build()
    http.newCall(request).execute().use { response ->
        if (!response.isSuccessful) return@use false
        Json.parseToJsonElement(response.body?.string().orEmpty())
            .jsonObject["status"]?.jsonPrimitive?.content == "ok"
    }
} catch (_: Exception) {
    false
}

internal fun collectorTargetGate(context: Context, onlineTarget: String): CollectorTargetGate {
    val healthClient = OkHttpClient.Builder()
        .connectTimeout(1, TimeUnit.SECONDS)
        .readTimeout(1, TimeUnit.SECONDS)
        .callTimeout(2, TimeUnit.SECONDS)
        .build()
    return CollectorTargetGate(
        onlineTarget = onlineTarget,
        usbConnected = { isUsbDataLink(context) },
        localHealthy = { localCollectorHealthy(healthClient, it) },
    )
}
