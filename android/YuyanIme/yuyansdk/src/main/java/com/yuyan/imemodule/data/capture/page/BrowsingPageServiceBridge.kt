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
            else budget.reserveNow(packageName).also { status("budget_${it.name.lowercase()}", packageName) } == BrowseBudgetResult.ALLOWED
        },
        capture = { snapshot, current ->
            val navigation = videoSequence.get()
            val consent = CollectionConsent.epoch
            val policy = ChatCaptureSettings.revision()
            reader.readAndPersist(outbox, snapshot.page.packageName, snapshot.page.windowId, snapshot.bounds,
                snapshot.labels, chatVerified = snapshot.chatVerified, current = {
                    current() && navigation == videoSequence.get() && consent == CollectionConsent.epoch &&
                        policy == ChatCaptureSettings.revision() && eligible() && ChatCaptureSettings.rule(snapshot.page.packageName).enabled
                }, acceptKind = ::acceptOrdinaryPageKind,
                captureKey = PageFrameCaptureKey(snapshot.page.packageName, snapshot.page.windowId, navigation, consent, policy))
        },
        outcome = ::status,
        persistentIntervalRemaining = { packageName -> budget.remainingIntervalNow(packageName) },
        shouldCapture = { snapshot, decision ->
            val visit = videoVisits?.activeVisit()
            val platform = if (snapshot.page.packageName == "com.tencent.mm") "wechat" else "douyin"
            acceptOrdinaryPageKind(decision.kind) && !(visit?.platform == platform &&
                visit.videoKey == "feed-window:${snapshot.page.windowId}")
        },
    )
    // 支付探测与视频观察独立于普通页面三分钟调度；复用同一物理帧，仅事件触发。
    private val videoSequence = java.util.concurrent.atomic.AtomicLong()
    private var videoObservation: Job? = null
    private var videoFirstFrame: Job? = null
    private val videoLifetime = SupervisorJob(BrowsingPageWorker.scope.coroutineContext[Job])
    private val videoScope = CoroutineScope(BrowsingPageWorker.scope.coroutineContext + videoLifetime)
    private val videoBudget = BrowsingCaptureBudgetStore(context, videoFrames = true)
    private val paymentBudget = BrowsingCaptureBudgetStore(context, paymentPages = true)
    private val videoCallSchedule = WechatVideoCallScreenshotSchedule()
    @Volatile private var videoCallFrame: Job? = null
    private val callSignals = com.yuyan.imemodule.data.callrecording.WechatCallSignals.connect { active, source ->
        val recheck = videoCallSchedule.onCallSignal(active, source)
        if (!active || source != "wechat_video") videoCallFrame?.cancel()
        if (recheck) videoScope.launch {
            // 接通可以只更新原呼叫窗口内容；通知只唤起核验，不直接授权截图。
            if (eligible()) observePriorityPage()
        }
    }

    private fun cancelVideoObservation() {
        videoSequence.incrementAndGet()
        reader.clearSharedFrame()
        videoObservation?.cancel()
        videoFirstFrame?.cancel()
        videoCallSchedule.invalidate()
        videoCallFrame?.cancel()
    }

    private fun observePriorityPage() {
        cancelVideoObservation()
        val sequence = videoSequence.get()
        val enteredElapsed = SystemClock.elapsedRealtime()
        val enteredWall = System.currentTimeMillis()
        val consent = CollectionConsent.epoch
        val policy = ChatCaptureSettings.revision()
        fun current() = sequence == videoSequence.get() && eligible() &&
            consent == CollectionConsent.epoch && policy == ChatCaptureSettings.revision()
        videoObservation = videoScope.launch {
            try {
                delay(800)
                previousDrain?.join()
                if (!current()) return@launch
                val snapshot = resolveBrowsingWindowHint(current = ::current, read = {
                    val active = readActiveBrowsingWindow(service)
                    if (active == null || active.first != "com.tencent.mm") videoCallSchedule.leave()
                    if (active == null || !ChatCaptureSettings.rule(active.first).enabled) null
                    else readBrowsingPageSnapshot(service, BrowsePageToken(active.first, active.second, sequence))
                }, wait = { delay(it) }) ?: return@launch
                if (!current()) return@launch
                val decision = PageCapturePolicy.classify(snapshot.page.packageName, snapshot.bounds, snapshot.labels, chatVerified = snapshot.chatVerified)
                if (decision.kind != null && decision.kind != PageKind.VIDEO_CALL) videoCallSchedule.leave()
                if (decision.kind == PageKind.VIDEO_CALL) {
                    captureVideoCallAfterStable(snapshot, sequence)
                } else if (decision.kind == PageKind.MEDIA_FEED) {
                    val generation = videoVisits?.generation() ?: return@launch
                    videoVisits.observeFeed(generation, snapshot.page.packageName, snapshot.page.windowId, enteredElapsed,
                        enteredWall, ::current) { visit ->
                        if (visit.firstImage == null && videoFirstFrame?.isActive != true && current()) {
                            videoFirstFrame = capturePriorityFrame(snapshot, sequence, visit, decision, enteredElapsed, enteredWall)
                        }
                    }
                } else if (decision.kind == PageKind.PAYMENT || decision.reason == "insufficient_evidence") {
                    // 微信支付可能是空树；先作有预算的同帧识别，不能因普通页面冷却而丢掉它。
                    videoFirstFrame = capturePriorityFrame(snapshot, sequence, null, decision, enteredElapsed, enteredWall)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { status("video_observation_failed") }
        }
    }

    private fun capturePriorityFrame(snapshot: BrowsePageSnapshot, sequence: Long, visit: VideoVisit?,
        decision: PageDecision, enteredElapsed: Long, enteredWall: Long): Job =
        videoScope.launch {
            val consent = CollectionConsent.epoch
            val policy = ChatCaptureSettings.revision()
            val generation = videoVisits?.generation()
            fun current() = sequence == videoSequence.get() && eligible() &&
                consent == CollectionConsent.epoch && policy == ChatCaptureSettings.revision() &&
                ChatCaptureSettings.rule(snapshot.page.packageName).enabled &&
                (visit == null || videoVisits?.activeVisit()?.id == visit.id) && videoVisits?.generation() == generation
            try {
                if (!current()) return@launch
                val feed = decision.kind == PageKind.MEDIA_FEED
                if (feed) {
                    if (!awaitVideoFeedDwell(enteredElapsed, SystemClock::elapsedRealtime, ::current) { delay(it) }) return@launch
                    if (!ImageUploadRuntime.awaitBackgroundWorkAllowed(::current) || !current()) return@launch
                }
                if (decision.kind == PageKind.PAYMENT || decision.reason == "insufficient_evidence") {
                    // 刚从聊天输入跳转时可仍处于输入保护期；同一导航等到允许，不直接漏掉支付。
                    if (!ImageUploadRuntime.awaitBackgroundWorkAllowed(::current) || !current()) return@launch
                    // 仅本次导航保留一次秒级延后；离开页面即取消，不轮询等待额度恢复。
                    val remaining = paymentBudget.remainingIntervalNow()
                    if (remaining != null && remaining in 1L..1_000L) delay(remaining)
                    if (!current() || !ImageUploadRuntime.isBackgroundWorkAllowed() || !outbox.hasCapacity()) return@launch
                }
                if (!ImageUploadRuntime.isBackgroundWorkAllowed() || !outbox.hasCapacity()) return@launch
                val kinds = reservePriorityPageKinds(decision,
                    reservePayment = {
                        paymentBudget.reserveNow().also { status("budget_${it.name.lowercase()}", snapshot.page.packageName) } == BrowseBudgetResult.ALLOWED
                    },
                    reserveVideo = {
                        videoVisits != null && (visit != null || videoVisits.activeVisit() == null) &&
                            videoBudget.reserveNow() == BrowseBudgetResult.ALLOWED
                    }, feedReady = feed)
                fun deferUnknownFeed() {
                    if (decision.kind == null && decision.reason == "insufficient_evidence" && current()) {
                        videoFirstFrame = capturePriorityFrame(snapshot, sequence, null,
                            PageDecision(PageKind.MEDIA_FEED, "deferred_feed_probe"), enteredElapsed, enteredWall)
                    }
                }
                if (!current()) return@launch
                if (kinds.isEmpty()) { deferUnknownFeed(); return@launch }
                var sawFeed = false
                val result = reader.readAndPersist(outbox, snapshot.page.packageName, snapshot.page.windowId, snapshot.bounds,
                    snapshot.labels, chatVerified = snapshot.chatVerified, current = ::current,
                    acceptKind = { it in kinds },
                    // 视频必须取新帧，避免在途早期探测回填缓存后被复用；物理截图槽仍共用。
                    captureKey = if (feed) null else PageFrameCaptureKey(snapshot.page.packageName, snapshot.page.windowId, sequence, consent, policy),
                    onPersisted = { frame, result, _, _ ->
                        // 未知树的早期探测只能确认通话候选，不能提前落盘通话图。
                        if (current() && frame.page.kind == PageKind.VIDEO_CALL)
                            captureVideoCallAfterStable(snapshot, sequence)
                        if (current() && generation != null && frame.page.kind == PageKind.MEDIA_FEED) {
                            sawFeed = true
                            if (!feed) return@readAndPersist
                            if (visit == null) videoVisits.pageCaptured(generation, snapshot.page.packageName, PageKind.MEDIA_FEED,
                                result, enteredElapsed, enteredWall, snapshot.page.windowId, consent, policy, ::current, freshFrame = true)
                            else videoVisits.attachFirstFrame(generation, visit, result, consent, policy, ::current)
                        }
                    })
                status(result.status.name.lowercase(), snapshot.page.packageName)
                if (sawFeed) deferUnknownFeed()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { status("capture_or_storage_failed", snapshot.page.packageName) }
        }

    private fun captureVideoCallAfterStable(snapshot: BrowsePageSnapshot, sequence: Long) {
        val consent = CollectionConsent.epoch
        val policy = ChatCaptureSettings.revision()
        val ticket = videoCallSchedule.observe(snapshot.page.packageName, snapshot.page.windowId,
            SystemClock.elapsedRealtime()) ?: return
        fun current() = videoCallSchedule.isCurrent(ticket) && sequence == videoSequence.get() &&
            eligible() && consent == CollectionConsent.epoch && policy == ChatCaptureSettings.revision() &&
            ChatCaptureSettings.rule(snapshot.page.packageName).enabled
        videoCallFrame = videoScope.launch {
            try {
                delay(5_000)
                if (!current() || !ImageUploadRuntime.awaitBackgroundWorkAllowed(::current) || !current() ||
                    !outbox.hasCapacity()) return@launch
                val fresh = readBrowsingPageSnapshot(service, snapshot.page) ?: return@launch
                if (!current() || !videoCallSchedule.take(ticket, SystemClock.elapsedRealtime())) return@launch
                // 不复用5秒前的探测图；重新取帧并核对当前画面仍为已接通视频通话。
                val result = reader.readAndPersist(outbox, fresh.page.packageName, fresh.page.windowId, fresh.bounds,
                    fresh.labels, chatVerified = fresh.chatVerified, current = ::current,
                    acceptKind = { it == PageKind.VIDEO_CALL })
                if (result.status in setOf(PageWriteStatus.SAVED, PageWriteStatus.DUPLICATE)) videoCallSchedule.saved(ticket)
                status(result.status.name.lowercase(), fresh.page.packageName)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { status("capture_or_storage_failed", snapshot.page.packageName) }
        }
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
        observePriorityPage()
    }
    private fun status(code: String, packageName: String? = null) {
        android.util.Log.i("BrowsingPageCapture", code)
        if (packageName != null) PageCaptureDiagnostics.capture(context, packageName, code)
    }

    fun onEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.packageName?.toString() != "com.tencent.mm")
            videoCallSchedule.leave()
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> observePriorityPage()
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
    fun invalidate() { cancelWindowHint(); cancelVideoObservation(); videoCallSchedule.leave(); driver.invalidate() }
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        cancelWindowHint()
        cancelVideoObservation()
        callSignals.close()
        videoLifetime.cancel()
        runCatching { context.unregisterReceiver(receiver) }
        val stopped = driver.close()
        BrowsingPageWorker.lastDrain = BrowsingPageWorker.scope.launch {
            stopped.join()
            videoLifetime.join()
            runCatching { try { outbox.close() } finally { try { budget.close() } finally { try { videoBudget.close() } finally { paymentBudget.close() } } } }
                .onFailure { status("storage_close_failed") }
        }
    }
}

/** 同一次拓扑事件最多补核验一次；无候选截帧、无预算操作，失效或取消后不重放。 */
internal suspend fun <T> resolveBrowsingWindowHint(
    current: () -> Boolean,
    read: () -> T?,
    wait: suspend (Long) -> Unit,
): T? {
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
