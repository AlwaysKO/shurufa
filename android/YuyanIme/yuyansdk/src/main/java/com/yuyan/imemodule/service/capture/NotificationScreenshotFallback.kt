package com.yuyan.imemodule.service.capture

import android.content.Context
import com.yuyan.imemodule.data.capture.ui.CancellableTask
import com.yuyan.imemodule.data.capture.ui.IntRect
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque

data class NotificationScreenshotFallbackRequest(
    val notificationKey: String,
    val postedAtMillis: Long,
)

class NotificationScreenshotFallbackQueue(
    private val clock: () -> Long = System::currentTimeMillis,
    private val maximumAgeMillis: Long = 10 * 60 * 1_000L,
    private val maximumSize: Int = 20,
) {
    private val pending = ArrayDeque<NotificationScreenshotFallbackRequest>()

    @Synchronized
    fun offer(request: NotificationScreenshotFallbackRequest) {
        discardExpired()
        if (pending.contains(request)) return
        while (pending.size >= maximumSize) pending.removeFirst()
        pending.addLast(request)
    }

    @Synchronized
    fun peek(): NotificationScreenshotFallbackRequest? {
        discardExpired()
        return pending.peekFirst()
    }

    @Synchronized
    fun removeIfSame(request: NotificationScreenshotFallbackRequest) {
        if (pending.peekFirst() == request) pending.removeFirst()
    }

    private fun discardExpired() {
        while (pending.peekFirst()?.let { clock() - it.postedAtMillis > maximumAgeMillis } == true) {
            pending.removeFirst()
        }
    }
}

class NotificationScreenshotFallbackStore(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maximumAgeMillis: Long = 10 * 60 * 1_000L,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun offer(request: NotificationScreenshotFallbackRequest) {
        synchronized(STORE_LOCK) {
            val requests = readValidRequests().toMutableList()
            if (request !in requests) {
                while (requests.size >= MAXIMUM_SIZE) requests.removeAt(0)
                requests += request
                write(requests)
            }
        }
    }

    fun load(): NotificationScreenshotFallbackRequest? {
        synchronized(STORE_LOCK) {
            val requests = readValidRequests()
            write(requests)
            return requests.firstOrNull()
        }
    }

    fun removeIfSame(request: NotificationScreenshotFallbackRequest) {
        synchronized(STORE_LOCK) {
            val requests = readValidRequests().toMutableList()
            if (requests.firstOrNull() == request) requests.removeAt(0)
            write(requests)
        }
    }

    private fun readValidRequests(): List<NotificationScreenshotFallbackRequest> {
        val encoded = preferences.getString(KEY_REQUESTS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(encoded)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val request = NotificationScreenshotFallbackRequest(
                        notificationKey = item.getString(JSON_NOTIFICATION_KEY),
                        postedAtMillis = item.getLong(JSON_POSTED_AT),
                    )
                    if (request.postedAtMillis > 0L && clock() - request.postedAtMillis <= maximumAgeMillis) add(request)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun write(requests: List<NotificationScreenshotFallbackRequest>) {
        if (requests.isEmpty()) {
            preferences.edit().remove(KEY_REQUESTS).commit()
            return
        }
        val array = JSONArray()
        requests.forEach { request ->
            array.put(JSONObject().put(JSON_NOTIFICATION_KEY, request.notificationKey).put(JSON_POSTED_AT, request.postedAtMillis))
        }
        preferences.edit().putString(KEY_REQUESTS, array.toString()).commit()
    }

    private companion object {
        const val PREFERENCES_NAME = "notification_screenshot_fallback_pending"
        const val KEY_REQUESTS = "requests"
        const val JSON_NOTIFICATION_KEY = "notification_key"
        const val JSON_POSTED_AT = "posted_at"
        const val MAXIMUM_SIZE = 20
        val STORE_LOCK = Any()
    }
}

object NotificationScreenshotFallbackBridge {
    private var handler: ((NotificationScreenshotFallbackRequest) -> Unit)? = null

    @Synchronized
    fun connect(callback: (NotificationScreenshotFallbackRequest) -> Unit): CancellableTask {
        handler = callback
        return CancellableTask {
            synchronized(this) {
                if (handler === callback) handler = null
            }
        }
    }

    @Synchronized
    fun request(request: NotificationScreenshotFallbackRequest) {
        handler?.invoke(request)
    }
}

internal fun shouldCaptureNotificationFallback(
    screenLocked: Boolean,
    foregroundPackage: String?,
    inputMethodVisible: Boolean,
): Boolean = !screenLocked && foregroundPackage == WECHAT_PACKAGE && !inputMethodVisible

internal fun notificationFallbackBounds(windowBounds: IntRect): IntRect {
    val height = windowBounds.bottom - windowBounds.top
    return IntRect(
        left = windowBounds.left,
        top = windowBounds.top + height * 3 / 100,
        right = windowBounds.right,
        bottom = windowBounds.top + height * 90 / 100,
    )
}

internal const val WECHAT_PACKAGE = "com.tencent.mm"
