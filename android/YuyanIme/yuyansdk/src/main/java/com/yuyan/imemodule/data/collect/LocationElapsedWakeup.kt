package com.yuyan.imemodule.data.collect

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.SystemClock

/** A single lifecycle-bound backup for an existing deadline, never a periodic poll. */
internal class LocationElapsedWakeup(
    private val context: Context,
    private val handler: Handler,
    private val onDeadline: () -> Unit,
) {
    private val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val action = context.packageName + ".LOCATION_DEADLINE"
    private val pending = PendingIntent.getBroadcast(context, 2108,
        Intent(action).setPackage(context.packageName), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private var at: Long? = null
    private var generation = 0L
    private var cancelListener: (() -> Unit)? = null
    private var closed = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == action) fire(generation)
        }
    }
    init {
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        } else {
            // Same signature permission supplied by AndroidX Core's merged manifest
            // for its pre-33 NOT_EXPORTED receiver implementation.
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, IntentFilter(action),
                context.packageName + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION", handler)
        }
    }

    fun schedule(deadlineMs: Long?) {
        if (closed || at == deadlineMs) return
        cancel()
        if (deadlineMs == null) return
        at = deadlineMs
        val expected = generation
        val trigger = maxOf(deadlineMs, SystemClock.elapsedRealtime() + 1L)
        if (Build.VERSION.SDK_INT >= 24) {
            val listener = AlarmManager.OnAlarmListener { fire(expected) }
            // Android 12/13 may deny exact scheduling. No new special permission
            // is requested; the inexact idle alarm below is a best-effort fallback.
            if (runCatching {
                    manager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger,
                        "balanced-location-deadline", listener, handler)
                }.isSuccess) {
                cancelListener = { manager.cancel(listener) }
            }
        }
        // A normal exact alarm is deferred in Doze. Keep an idle-eligible backup
        // at the same deadline; whichever fires first cancels both paths.
        runCatching { manager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pending) }
    }

    private fun fire(expected: Long) {
        val deadline = at ?: return
        if (closed || expected != generation || SystemClock.elapsedRealtime() < deadline) return
        cancel()
        onDeadline()
    }

    fun cancel() {
        at = null
        generation++
        cancelListener?.let { runCatching(it) }
        cancelListener = null
        runCatching { manager.cancel(pending) }
    }

    fun close() {
        if (closed) return
        closed = true
        cancel()
        context.unregisterReceiver(receiver)
        pending.cancel()
    }
}
