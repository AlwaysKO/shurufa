package com.yuyan.imemodule.service.capture

import android.content.Context
import android.service.notification.NotificationListenerService
import com.yuyan.imemodule.data.capture.model.ChatPlatform
import com.yuyan.imemodule.data.capture.ui.CancellableTask
import com.yuyan.imemodule.data.capture.ui.IntRect
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque

data class NotificationScreenshotFallbackRequest(
    val notificationKey: String,
    val postedAtMillis: Long,
    val packageName: String = WECHAT_PACKAGE,
)

data class NotificationScreenshotFallbackDescriptor(
    val platform: ChatPlatform,
    val externalKey: String,
    val displayName: String,
    val messageText: String,
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
    preferencesName: String = PREFERENCES_NAME,
) {
    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

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

    fun offerReplacingNotification(request: NotificationScreenshotFallbackRequest) {
        synchronized(STORE_LOCK) {
            val requests = readValidRequests()
                .filterNot { it.notificationKey == request.notificationKey }
                .toMutableList()
            while (requests.size >= MAXIMUM_SIZE) requests.removeAt(0)
            requests += request
            write(requests)
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

    fun takeByNotificationKey(notificationKey: String): NotificationScreenshotFallbackRequest? {
        synchronized(STORE_LOCK) {
            val requests = readValidRequests()
            val selected = requests.lastOrNull { it.notificationKey == notificationKey }
            write(requests.filterNot { it.notificationKey == notificationKey })
            return selected
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
                        packageName = item.optString(JSON_PACKAGE_NAME, WECHAT_PACKAGE),
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
            array.put(
                JSONObject()
                    .put(JSON_NOTIFICATION_KEY, request.notificationKey)
                    .put(JSON_POSTED_AT, request.postedAtMillis)
                    .put(JSON_PACKAGE_NAME, request.packageName),
            )
        }
        preferences.edit().putString(KEY_REQUESTS, array.toString()).commit()
    }

    private companion object {
        const val PREFERENCES_NAME = "notification_screenshot_fallback_pending"
        const val KEY_REQUESTS = "requests"
        const val JSON_NOTIFICATION_KEY = "notification_key"
        const val JSON_POSTED_AT = "posted_at"
        const val JSON_PACKAGE_NAME = "package_name"
        const val MAXIMUM_SIZE = 20
        val STORE_LOCK = Any()
    }
}

internal fun shouldArmNotificationScreenshot(removalReason: Int): Boolean =
    removalReason == NotificationListenerService.REASON_CLICK

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
    targetPackage: String = WECHAT_PACKAGE,
): Boolean =
    targetPackage in NOTIFICATION_SCREENSHOT_PACKAGES &&
        !screenLocked &&
        foregroundPackage == targetPackage &&
        !inputMethodVisible

internal fun notificationScreenshotFallbackDescriptor(
    packageName: String,
): NotificationScreenshotFallbackDescriptor? = when (packageName) {
    WECHAT_PACKAGE -> NotificationScreenshotFallbackDescriptor(
        platform = ChatPlatform.WECHAT,
        externalKey = "wechat-hidden-notification",
        displayName = "微信（截图兜底）",
        messageText = "[微信新消息截图]",
    )
    DOUYIN_PACKAGE -> NotificationScreenshotFallbackDescriptor(
        platform = ChatPlatform.DOUYIN,
        externalKey = "douyin-hidden-notification",
        displayName = "抖音（截图兜底）",
        messageText = "[抖音新消息截图]",
    )
    else -> null
}

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
internal const val DOUYIN_PACKAGE = "com.ss.android.ugc.aweme"
private val NOTIFICATION_SCREENSHOT_PACKAGES = setOf(WECHAT_PACKAGE, DOUYIN_PACKAGE)
