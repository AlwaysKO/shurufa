package com.yuyan.imemodule.data.capture.page

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.*
import android.graphics.Rect
import android.os.*
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.yuyan.imemodule.data.capture.adapter.ChatCaptureSettings
import com.yuyan.imemodule.data.capture.media.WindowMediaCapturer
import com.yuyan.imemodule.data.capture.ui.AccessibilityTreeReader
import com.yuyan.imemodule.data.capture.ui.IntRect
import com.yuyan.imemodule.data.collect.CollectionConsent
import com.yuyan.imemodule.data.collect.ImageUploadRuntime
import kotlinx.coroutines.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** 进程级低优先级串行后台线程，无定时采样；数据库关闭不依赖聊天scope存活。 */
private object BrowsingPageWorker {
    private val dispatcher = Executors.newSingleThreadExecutor { action ->
        Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); action.run() },
            "browsing-page-capture").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    val scope = CoroutineScope(SupervisorJob() + dispatcher)
    @Volatile var lastDrain: Job? = null
}

internal class BrowsingPageServiceBridge(
    private val service: AccessibilityService,
    mediaCapturer: WindowMediaCapturer,
    private val videoVisits: VideoVisitMonitor? = null,
) {
    private val context = service.applicationContext
    private val closed = AtomicBoolean()
    private val hintGeneration = java.util.concurrent.atomic.AtomicLong()
    private var windowHint: Job? = null
    private val budget = BrowsingCaptureBudgetStore(context)
    private val outbox = PageCaptureOutbox(context)
    private val reader = LocalPageFrameReader(service, mediaCapturer)
    private val previousDrain = BrowsingPageWorker.lastDrain
    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    private val battery = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    private fun eligible(): Boolean {
        if (closed.get() || Build.VERSION.SDK_INT < 30 || !CollectionConsent.enabled(context) ||
            !power.isInteractive || keyguard.isKeyguardLocked || power.isPowerSaveMode) return false
        if (battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) in 0..14) return false
        return power.currentThermalStatus < PowerManager.THERMAL_STATUS_MODERATE
    }
    private val driver = BrowsingPageDriver(
        BrowsingPageWorker.scope, SystemClock::elapsedRealtime, { CollectionConsent.epoch }, ::eligible,
        awaitIdle = { current ->
            previousDrain?.join()
            ImageUploadRuntime.awaitBackgroundWorkAllowed(current)
        },
        wait = { delay(it) },
        hasCapacity = { outbox.hasCapacity() },
        read = { token ->
            if (!eligible() || !ImageUploadRuntime.isBackgroundWorkAllowed() ||
                !ChatCaptureSettings.rule(token.packageName).enabled) null
            else readBrowsingPageSnapshot(service, token)
        },
        reserve = { packageName ->
            if (!eligible() || !ImageUploadRuntime.isBackgroundWorkAllowed()) false
            else budget.reserveNow().also { status("budget_${it.name.lowercase()}", packageName) } == BrowseBudgetResult.ALLOWED
        },
        capture = { snapshot, current ->
            val visitGeneration = videoVisits?.generation()
            val visitConsent = CollectionConsent.epoch
            val visitPolicy = ChatCaptureSettings.revision()
            reader.readAndPersist(outbox, snapshot.page.packageName, snapshot.page.windowId, snapshot.bounds,
                snapshot.labels, chatVerified = snapshot.chatVerified, current = {
                    current() && eligible() && ChatCaptureSettings.rule(snapshot.page.packageName).enabled
                }, onPersisted = { frame, result, elapsed, wall ->
                    if (visitGeneration != null && current() && eligible() &&
                        ChatCaptureSettings.rule(snapshot.page.packageName).enabled)
                        frame.page.kind?.let { kind -> videoVisits?.pageCaptured(visitGeneration,
                            snapshot.page.packageName, kind, result, elapsed, wall, snapshot.page.windowId,
                            visitConsent, visitPolicy, stillCurrent = { current() && eligible() &&
                                ChatCaptureSettings.rule(snapshot.page.packageName).enabled }) }
                })
        },
        outcome = ::status,
        persistentIntervalRemaining = { budget.remainingIntervalNow() },
    )
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) invalidate()
        }
    }
    init {
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        try {
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("DEPRECATION") context.registerReceiver(receiver, filter)
        } catch (failure: Exception) {
            close()
            throw failure
        }
    }
    private fun status(code: String, packageName: String? = null) {
        android.util.Log.i("BrowsingPageCapture", code)
        if (packageName != null) PageCaptureDiagnostics.capture(context, packageName, code)
    }

    fun onEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                cancelWindowHint()
                driver.changed(event.packageName?.toString(), event.windowId, BrowsePageEvent.WINDOW)
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                cancelWindowHint()
                driver.changed(event.packageName?.toString(), event.windowId, BrowsePageEvent.SCROLL)
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                cancelWindowHint()
                val navigation = driver.invalidate()
                val ticket = hintGeneration.get()
                val epoch = CollectionConsent.epoch
                // 该事件经常没有包名/窗口ID，只保留一次后台活动窗口核验。
                windowHint = BrowsingPageWorker.scope.launch {
                    try {
                        delay(800)
                        if (hintGeneration.get() != ticket || CollectionConsent.epoch != epoch || !eligible()) return@launch
                        val active = resolveBrowsingWindowHint(
                            current = { hintGeneration.get() == ticket && CollectionConsent.epoch == epoch && eligible() },
                            read = { readActiveBrowsingWindow(service) },
                            wait = { delay(it) },
                        ) ?: return@launch
                        if (hintGeneration.get() == ticket && CollectionConsent.epoch == epoch && eligible())
                            driver.changedIfCurrent(navigation, epoch, active.first, active.second)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { status("window_unconfirmed") }
                }
            }
            // 播放像素、进度条、点赞数字等内容变化不触发周期截图。
        }
    }
    private fun cancelWindowHint() {
        hintGeneration.incrementAndGet()
        windowHint?.cancel()
        windowHint = null
    }
    fun invalidate() { cancelWindowHint(); driver.invalidate() }
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        cancelWindowHint()
        runCatching { context.unregisterReceiver(receiver) }
        val stopped = driver.close()
        BrowsingPageWorker.lastDrain = BrowsingPageWorker.scope.launch {
            stopped.join()
            runCatching { try { outbox.close() } finally { budget.close() } }
                .onFailure { status("storage_close_failed") }
        }
    }
}

/** 同一次拓扑事件最多补核验一次；无候选截帧、无预算操作，失效或取消后不重放。 */
internal suspend fun resolveBrowsingWindowHint(
    current: () -> Boolean,
    read: () -> Pair<String, Int>?,
    wait: suspend (Long) -> Unit,
): Pair<String, Int>? {
    if (!current()) return null
    val first = read()
    if (!current()) return null
    if (first != null) return first
    wait(800)
    if (!current()) return null
    val second = read()
    return second?.takeIf { current() }
}

/** 只在后台有界读已核实的活动应用；空树用窗口边界，不用事件包名猜底层App。 */
@Suppress("DEPRECATION")
internal fun readBrowsingPageSnapshot(service: AccessibilityService, token: BrowsePageToken): BrowsePageSnapshot? {
    if (token.packageName !in setOf("com.tencent.mm", "com.ss.android.ugc.aweme")) return null
    val windows = service.windows
    try {
        val active = windows.filter { it.isActive }
        if (active.any { it.type != AccessibilityWindowInfo.TYPE_APPLICATION }) return null
        val window = active.singleOrNull { it.id == token.windowId && it.type == AccessibilityWindowInfo.TYPE_APPLICATION } ?: return null
        val root = window.root ?: return null
        try {
            if (root.packageName?.toString() != token.packageName) return null
            val rect = Rect().also(window::getBoundsInScreen)
            if (rect.width() <= 0 || rect.height() <= 0) return null
            val bounds = IntRect(rect.left, rect.top, rect.right, rect.bottom)
            val snapshot = AccessibilityTreeReader(maxDepth = 30, maxNodes = 600).read(root) ?: return null
            val bounded = snapshot.copy(bounds = bounds)
            return BrowsePageSnapshot(token, bounds, PageCapturePolicy.labels(bounded), isBrowsingChatSnapshot(token.packageName, bounded))
        } finally { root.recycle() }
    } finally { windows.forEach { it.recycle() } }
}

internal fun isBrowsingChatSnapshot(packageName: String, snapshot: com.yuyan.imemodule.data.capture.ui.UiNodeSnapshot): Boolean {
    val parsed = com.yuyan.imemodule.data.capture.adapter.AdapterRegistry.forPackage(packageName)?.parse(snapshot)
        as? com.yuyan.imemodule.data.capture.adapter.ParseResult.Success ?: return false
    // 旧微信适配器也返回固定页面（列表/朋友圈），这些不是聊天，不能据此排除。
    val legacyPage = packageName == "com.tencent.mm" && parsed.viewport.titleBounds == null &&
        parsed.viewport.messages.isNotEmpty() && parsed.viewport.messages.all { it.metadata["capture_source"] == "wechat_page_screenshot" }
    return !legacyPage
}

@Suppress("DEPRECATION")
internal fun readActiveBrowsingWindow(service: AccessibilityService): Pair<String, Int>? {
    val windows = service.windows
    try {
        val active = windows.filter { it.isActive }.singleOrNull() ?: return null
        if (active.type != AccessibilityWindowInfo.TYPE_APPLICATION) return null
        val root = active.root ?: return null
        try {
            val pkg = root.packageName?.toString()?.takeIf { it in setOf("com.tencent.mm", "com.ss.android.ugc.aweme") } ?: return null
            return pkg to active.id
        } finally { root.recycle() }
    } finally { windows.forEach { it.recycle() } }
}
