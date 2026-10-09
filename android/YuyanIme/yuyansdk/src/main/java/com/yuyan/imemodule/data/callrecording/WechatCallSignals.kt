package com.yuyan.imemodule.data.callrecording

import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

internal data class WechatCallSignal(val active: Boolean, val source: String)

/** Only transient, explicit call-state evidence. Never retains notification titles or message bodies. */
internal class WechatCallSignalState {
    private data class Evidence(val key: String, val source: String, val postedAtMillis: Long)
    private var current: Evidence? = null

    fun notification(packageName: String, key: String, ongoing: Boolean, text: String?,
        isMessagingStyle: Boolean, postedAtMillis: Long, nowMillis: Long): WechatCallSignal? {
        if (packageName != WECHAT_PACKAGE || key.isBlank() || postedAtMillis <= 0 ||
            postedAtMillis > nowMillis || nowMillis - postedAtMillis > 30_000) return null
        val previous = current
        if (previous != null && postedAtMillis < previous.postedAtMillis) return null
        val source = if (!ongoing || isMessagingStyle) null else when (text?.trim()) {
            "语音通话中" -> "wechat_voice"
            "视频通话中" -> "wechat_video"
            else -> null
        }
        if (source == null) return if (previous?.key == key) clear() else null
        current = Evidence(key, source, postedAtMillis)
        return WechatCallSignal(true, source)
    }

    fun removed(packageName: String, key: String, postedAtMillis: Long): WechatCallSignal? {
        val previous = current ?: return null
        if (packageName != WECHAT_PACKAGE || key != previous.key || postedAtMillis < previous.postedAtMillis) return null
        return clear()
    }

    fun clear(): WechatCallSignal? {
        val previous = current ?: return null
        current = null
        return WechatCallSignal(false, previous.source)
    }

    private companion object { const val WECHAT_PACKAGE = "com.tencent.mm" }
}

/** A signal cannot start a service: the already-authorized recording service owns all audio checks. */
internal object WechatCallSignals {
    private val listeners = CopyOnWriteArraySet<(Boolean, String) -> Unit>()
    private val state = WechatCallSignalState()
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "wechat-call-signals").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    fun connect(listener: (Boolean, String) -> Unit): AutoCloseable {
        synchronized(listeners) { listeners.add(listener) }
        // Do not replay old notifications when a permission or consent is newly granted.
        return AutoCloseable {
            synchronized(listeners) {
                listeners.remove(listener)
                if (listeners.isEmpty()) worker.execute { state.clear() }
            }
        }
    }

    fun notification(packageName: String, key: String, ongoing: Boolean, text: String?,
        isMessagingStyle: Boolean, postedAtMillis: Long, nowMillis: Long = System.currentTimeMillis()) {
        if (packageName != "com.tencent.mm" || listeners.isEmpty()) return
        // Queue only a fixed status token, never a chat body or contact name.
        val status = text?.trim()?.takeIf { it == "语音通话中" || it == "视频通话中" }
        val queuedAt = System.nanoTime()
        dispatch {
            val queuedMillis = (System.nanoTime() - queuedAt).coerceAtLeast(0) / 1_000_000
            state.notification(packageName, key, ongoing, status, isMessagingStyle, postedAtMillis, nowMillis + queuedMillis)
        }
    }

    fun removed(packageName: String, key: String, postedAtMillis: Long) {
        if (packageName != "com.tencent.mm" || listeners.isEmpty()) return
        dispatch { state.removed(packageName, key, postedAtMillis) }
    }

    fun clear() {
        dispatch { state.clear() }
    }

    private fun dispatch(change: () -> WechatCallSignal?) {
        synchronized(listeners) {
            val recipients = listeners.toList()
            worker.execute {
                val signal = change() ?: return@execute
                recipients.forEach { listener ->
                    if (listener in listeners) runCatching { listener(signal.active, signal.source) }
                }
            }
        }
    }
}
