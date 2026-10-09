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
        return power.currentThermalStatus < PowerManager.THERMAL_STATUS_CRITICAL
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
            val navigation = videoSequence.get()
            val consent = CollectionConsent.epoch
            val policy = ChatCaptureSettings.revision()
            reader.readAndPersist(outbox, snapshot.page.packageName, snapshot.page.windowId, snapshot.bounds,
                snapshot.labels, chatVerified = snapshot.chatVerified, current = {
                    current() && navigation == videoSequence.get() && consent == CollectionConsent.epoch &&
                        policy == ChatCaptureSettings.revision() && eligible() && ChatCaptureSettings.rule(snapshot.page.packageName).enabled
                }, acceptKind = { it != PageKind.MEDIA_FEED },
                captureKey = PageFrameCaptureKey(snapshot.page.packageName, snapshot.page.windowId, navigation, consent, policy))
        },
        outcome = ::status,
        persistentIntervalRemaining = { budget.remainingIntervalNow() },
        shouldCapture = { snapshot, decision ->
            val visit = videoVisits?.activeVisit()
            val platform = if (snapshot.page.packageName == "com.tencent.mm") "wechat" else "douyin"
            decision.kind != PageKind.MEDIA_FEED && !(visit?.platform == platform &&
                visit.videoKey == "feed-window:${snapshot.page.windowId}")
        },
    )
    // 视频观察独立于普通页面三分钟调度；仅事件触发，无持续取帧。
    private val videoSequence = java.util.concurrent.atomic.AtomicLong()
    private var videoObservation: Job? = null
    private var videoFirstFrame: Job? = null
    private val videoLifetime = SupervisorJob(BrowsingPageWorker.scope.coroutineContext[Job])
    private val videoScope = CoroutineScope(BrowsingPageWorker.scope.coroutineContext + videoLifetime)
    private val videoBudget = BrowsingCaptureBudgetStore(context, videoFrames = true)

    private fun cancelVideoObservation() {
        videoSequence.incrementAndGet()
        reader.clearSharedFrame()
        videoObservation?.cancel()
        videoFirstFrame?.cancel()
    }

    private fun observeVideoPage() {
        cancelVideoObservation()
        val sequence = videoSequence.get()
        val consent = CollectionConsent.epoch
        val policy = ChatCaptureSettings.revision()
        fun current() = sequence == videoSequence.get() && eligible() &&
            consent == CollectionConsent.epoch && policy == ChatCaptureSettings.revision()
        videoObservation = videoScope.launch {
            try {
                delay(800)
                previousDrain?.join()
                if (!current()) return@launch
                val active = readActiveBrowsingWindow(service) ?: return@launch
                if (!ChatCaptureSettings.rule(active.first).enabled) return@launch
                val snapshot = readBrowsingPageSnapshot(service, BrowsePageToken(active.first, active.second, sequence)) ?: return@launch
                if (!current()) return@launch
                val decision = PageCapturePolicy.classify(active.first, snapshot.bounds, snapshot.labels, chatVerified = snapshot.chatVerified)
                if (decision.kind == PageKind.MEDIA_FEED) {
                    val generation = videoVisits?.generation() ?: return@launch
                    videoVisits.observeFeed(generation, active.first, active.second, SystemClock.elapsedRealtime(),
                        System.currentTimeMillis(), ::current) { visit ->
                        if (visit.firstImage == null && videoFirstFrame?.isActive != true && current()) {
                            videoFirstFrame = captureVideoFirstFrame(snapshot, sequence, visit)
                        }
                    }
                } else if (decision.reason == "insufficient_evidence" && videoVisits?.activeVisit() == null) {
                    // 空树不能直接计时；候选物理帧仍须通过同帧安全分类。
                    videoFirstFrame = captureVideoFirstFrame(snapshot, sequence, null)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { status("video_observation_failed") }
        }
    }

    private fun captureVideoFirstFrame(snapshot: BrowsePageSnapshot, sequence: Long, visit: VideoVisit?): Job =
        videoScope.launch {
            val consent = CollectionConsent.epoch
            val policy = ChatCaptureSettings.revision()
            val generation = videoVisits?.generation() ?: return@launch
            fun current() = sequence == videoSequence.get() && eligible() &&
                consent == CollectionConsent.epoch && policy == ChatCaptureSettings.revision() &&
                ChatCaptureSettings.rule(snapshot.page.packageName).enabled &&
                (visit == null || videoVisits.activeVisit()?.id == visit.id) && videoVisits.generation() == generation
            try {
                if (!current() || !ImageUploadRuntime.isBackgroundWorkAllowed() || !outbox.hasCapacity()) return@launch
                if (videoBudget.reserveNow() != BrowseBudgetResult.ALLOWED) return@launch
                reader.readAndPersist(outbox, snapshot.page.packageName, snapshot.page.windowId, snapshot.bounds,
                    snapshot.labels, chatVerified = snapshot.chatVerified, current = ::current,
                    acceptKind = { it == PageKind.MEDIA_FEED },
                    captureKey = PageFrameCaptureKey(snapshot.page.packageName, snapshot.page.windowId, sequence, consent, policy),
                    onPersisted = { frame, result, elapsed, wall ->
                        if (current() && frame.page.kind == PageKind.MEDIA_FEED) {
                            if (visit == null) videoVisits.pageCaptured(generation, snapshot.page.packageName, PageKind.MEDIA_FEED,
                                result, elapsed, wall, snapshot.page.windowId, consent, policy, ::current, freshFrame = true)
                            else videoVisits.attachFirstFrame(generation, visit, result, consent, policy, ::current)
                        }
                    })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { status("video_frame_failed") }
        }

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
        observeVideoPage()
    }
    private fun status(code: String, packageName: String? = null) {
        android.util.Log.i("BrowsingPageCapture", code)
        if (packageName != null) PageCaptureDiagnostics.capture(context, packageName, code)
    }

    fun onEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> observeVideoPage()
        }
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
    fun invalidate() { cancelWindowHint(); cancelVideoObservation(); driver.invalidate() }
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        cancelWindowHint()
        cancelVideoObservation()
        videoLifetime.cancel()
        runCatching { context.unregisterReceiver(receiver) }
        val stopped = driver.close()
        BrowsingPageWorker.lastDrain = BrowsingPageWorker.scope.launch {
            stopped.join()
            videoLifetime.join()
            runCatching { try { outbox.close() } finally { try { budget.close() } finally { videoBudget.close() } } }
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
