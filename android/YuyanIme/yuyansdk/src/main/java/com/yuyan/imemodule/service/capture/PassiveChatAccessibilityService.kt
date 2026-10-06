package com.yuyan.imemodule.service.capture

import com.yuyan.imemodule.data.capture.CaptureTrace
import com.yuyan.imemodule.data.capture.CaptureStage
import com.yuyan.imemodule.expression.send.WechatExpressionConfirmation
import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import com.yuyan.imemodule.data.capture.media.isWechatNonChatTree
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.yuyan.imemodule.data.capture.ui.AccessibilityTreeReader
import com.yuyan.imemodule.data.capture.ui.CoroutineDebounceScheduler
import com.yuyan.imemodule.data.capture.ui.UiNodeSnapshot
import com.yuyan.imemodule.data.capture.ui.ViewportDebouncer
import com.yuyan.imemodule.data.capture.ui.stableTreeSignature
import com.yuyan.imemodule.data.capture.ui.isUsableAccessibilitySnapshot
import com.yuyan.imemodule.data.capture.ui.preferredAccessibilitySnapshot
import com.yuyan.imemodule.data.capture.CaptureCoordinator
import com.yuyan.imemodule.data.capture.CapturePersistResult
import com.yuyan.imemodule.data.capture.RoomCaptureOutboxStore
import com.yuyan.imemodule.data.capture.adapter.ParseResult
import com.yuyan.imemodule.data.capture.model.stableKeyOrNull
import com.yuyan.imemodule.data.capture.adapter.AdapterRegistry
import com.yuyan.imemodule.data.capture.adapter.DouyinChatAdapter
import com.yuyan.imemodule.data.capture.adapter.DouyinCaptureDiagnostics
import com.yuyan.imemodule.data.capture.db.CaptureDatabase
import com.yuyan.imemodule.data.capture.media.WindowMediaCapturer
import com.yuyan.imemodule.data.capture.media.WindowScreenshotter
import com.yuyan.imemodule.data.capture.media.MediaCaptureRequest
import com.yuyan.imemodule.data.capture.media.MlKitWechatScreenshotIdentityResolver
import com.yuyan.imemodule.data.capture.media.TitleOcrInput
import com.yuyan.imemodule.data.capture.isWechatConversationList
import com.yuyan.imemodule.data.capture.wechatListMetadata
import com.yuyan.imemodule.data.capture.wechatListConversation
import com.yuyan.imemodule.data.capture.confirmedContent
import com.yuyan.imemodule.data.capture.media.ScreenshotContentInput
import com.yuyan.imemodule.data.capture.media.wechatTitleBand
import com.yuyan.imemodule.data.capture.media.ScreenshotConversationIdentityResolver
import com.yuyan.imemodule.data.capture.media.unresolvedWechatScreenshotIdentity
import com.yuyan.imemodule.data.capture.media.persistScreenshotBeforeConfirmation
import com.yuyan.imemodule.data.capture.media.isPeerTypingConversationTitle
import com.yuyan.imemodule.data.capture.media.ScreenshotConversationIdentity
import com.yuyan.imemodule.data.capture.net.CaptureUploader
import com.yuyan.imemodule.data.capture.model.CapturedConversation
import com.yuyan.imemodule.data.capture.model.CapturedMessage
import com.yuyan.imemodule.data.capture.model.ChatMessageType
import com.yuyan.imemodule.data.capture.model.ChatPlatform
import com.yuyan.imemodule.data.capture.model.ConversationType
import com.yuyan.imemodule.data.capture.ui.CancellableTask
import com.yuyan.imemodule.data.capture.ui.IntRect
import com.yuyan.imemodule.data.collect.CollectionConsent
import com.yuyan.imemodule.data.collect.DataCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import com.yuyan.imemodule.data.collect.LatestIdleWork
import com.yuyan.imemodule.data.collect.ImageUploadRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import com.yuyan.imemodule.data.capture.ActiveChatContext
import com.yuyan.imemodule.data.capture.ActiveChatContextStore
import com.yuyan.imemodule.data.capture.model.ChatDirection

/**
 * 聊天采集保持只读。图片确认观察独立于采集，仅按用户发送操作清理本次原输入。
 */
class PassiveChatAccessibilityService : AccessibilityService() {
    private var navigationCapture: com.yuyan.imemodule.data.navigation.NavigationCapture? = null
    private val douyinDiagnostics by lazy { DouyinCaptureDiagnostics(this) }
    private val backgroundDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val backgroundScope = CoroutineScope(SupervisorJob() + backgroundDispatcher)
    private val eventReads = LatestIdleWork(backgroundScope, ImageUploadRuntime::awaitBackgroundWorkAllowed)
    private val stableReads = LatestIdleWork(backgroundScope, ImageUploadRuntime::awaitBackgroundWorkAllowed)
    private val foregroundReads = LatestIdleWork(backgroundScope, ImageUploadRuntime::awaitBackgroundWorkAllowed)
    private val screenshotReads = LatestIdleWork(backgroundScope, ImageUploadRuntime::awaitBackgroundWorkAllowed)
    private val fallbackReads = LatestIdleWork(backgroundScope, ImageUploadRuntime::awaitBackgroundWorkAllowed)
    private val treeReader = AccessibilityTreeReader()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val snapshotGeneration = AtomicLong(0)
    private val fallbackRetryGeneration = AtomicLong(0)
    private val fallbackQueue = NotificationScreenshotFallbackQueue()
    private val fallbackCaptureMutex = Mutex()
    private val emptyTreeCaptureMutex = Mutex()
    private val scrollGate = ScrollCaptureGate(CoroutineDebounceScheduler(backgroundScope), ::onScrollStopped)

    private fun onScrollStopped(scope: ScreenshotScope) {
        val stopToken = captureRequestGeneration.get()
        mainHandler.post {
            if (!destroyed && captureRequestGeneration.get() == stopToken &&
                screenshotIdentityGeneration.get() == scope.generation && !scrollGate.blocks(scope)) {
                val root = rootInActiveWindow
                val packageName = try {
                    root?.packageName?.toString()?.takeIf { root.windowId == scope.window }
                } finally { root?.let(::recycleRoot) }
                if (packageName in SUPPORTED_PACKAGES) {
                    CaptureTrace.record(CaptureStage.SCROLL_STOPPED, scope.window, scope.generation)
                    captureCurrentForegroundViewport(requireNotNull(packageName), scrollResumeOnly = true)
                    pendingFallbackRequest()?.takeIf { it.packageName == packageName }?.let {
                        scheduleFallback(it, "scroll:${fallbackRetryGeneration.incrementAndGet()}")
                    }
                }
            }
        }
    }
    private val screenshotUpdates = ScreenshotUpdatePolicy()
    private val emptyCandidates = EmptyTreeCandidateProbe()
    private var typingRecheck: Runnable? = null
    private val screenshotGate = ScreenshotRequestGate()
    private val updateSequence = AtomicLong()
    @Volatile private var destroyed = false
    private val emptyUpdateDebouncer = ViewportDebouncer<ScreenshotScope>(
        scheduler = CoroutineDebounceScheduler(backgroundScope),
        onStable = { scope ->
            mainHandler.post {
                if (screenshotUpdates.accepts(scope.window, scope.generation, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)) {
                    captureScreenshotInScope(scope)
                }
            }
        },
    )
    private val screenshotIdentityGeneration = AtomicLong(0)
    private val captureRequestGeneration = AtomicLong(0)
    private var captureDatabase: CaptureDatabase? = null
    private var coordinator: CaptureCoordinator? = null
    private var mediaCapturer: WindowMediaCapturer? = null
    private var screenshotIdentityResolver: ScreenshotConversationIdentityResolver? = null
    private var fallbackConnection: CancellableTask? = null
    private var foregroundCaptureConnection: CancellableTask? = null
    private var fallbackStore: NotificationScreenshotFallbackStore? = null
    private val debouncer = ViewportDebouncer(
        scheduler = CoroutineDebounceScheduler(backgroundScope),
        immediateOnContextChange = true,
        onContextInvalidated = { captureRequestGeneration.incrementAndGet() },
        onStable = ::onStableViewport,
    )
    private val fallbackDebouncer = ViewportDebouncer(
        scheduler = CoroutineDebounceScheduler(backgroundScope),
        stableDelayMillis = FALLBACK_DELAY_MILLIS,
        onStable = ::onStableFallbackRequest,
    )

    private fun resetScreenshotIdentity() {
        eventReads.cancel()
        stableReads.cancel()
        foregroundReads.cancel()
        screenshotReads.cancel()
        fallbackReads.cancel()
        typingRecheck?.let(mainHandler::removeCallbacks)
        typingRecheck = null
        scrollGate.clear()
        screenshotUpdates.clear()
        emptyCandidates.clear()
        emptyUpdateDebouncer.close()
        screenshotGate.clearPending()
        captureRequestGeneration.incrementAndGet()
        val generation = screenshotIdentityGeneration.incrementAndGet()
        CaptureTrace.record(CaptureStage.RESET, generation = generation)
        screenshotIdentityResolver?.reset()
        coordinator?.resetConversationIdentity()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        com.yuyan.imemodule.data.collect.GameForegroundMonitor.event(event)
        com.yuyan.imemodule.data.redpacket.GroupRedPacketAssistant.event(event)
        navigationCapture?.onEvent(event)
        WechatExpressionConfirmation.event(event)
        if (!CollectionConsent.enabled(this)) {
            resetScreenshotIdentity()
            debouncer.close()
            return
        }
        val packageName = event.packageName?.toString() ?: return
        if (packageName !in SUPPORTED_PACKAGES) {
            // 其他App只观察窗口切换元信息以取消旧任务；不读取它们的文字或页面树。
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                packageName != applicationContext.packageName) {
                resetScreenshotIdentity()
                snapshotGeneration.incrementAndGet()
                debouncer.close()
            }
            return
        }
        val eventText = listOfNotNull(
            event.text?.joinToString(" "),
            event.contentDescription?.toString(),
        ).joinToString(" ")
        CaptureTrace.record(CaptureStage.EVENT, event.windowId, screenshotIdentityGeneration.get(), event.eventType, eventText.contains("转文字"))
        if (shouldResetScreenshotIdentity(packageName, event.eventType, eventText, event.className?.toString())) resetScreenshotIdentity()
        val scrollScope = ScreenshotScope(event.windowId, screenshotIdentityGeneration.get())
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            scrollGate.scrolled(scrollScope)
            snapshotGeneration.incrementAndGet()
            captureRequestGeneration.incrementAndGet()
            debouncer.close()
            emptyUpdateDebouncer.close()
            fallbackDebouncer.close()
            CaptureTrace.record(CaptureStage.SCROLL_PENDING, scrollScope.window, scrollScope.generation)
            return
        }
        if (scrollGate.blocks(scrollScope)) return
        val regularCapture = shouldCaptureForegroundChatEvent(event.eventType, event.className?.toString(), eventText)
        val possibleEmptyTreeCapture = shouldCaptureEmptyTreeWeChatOpen(
            eventType = event.eventType,
            className = event.className?.toString(),
            visibleText = eventText,
            activeTreeUsable = false,
            sourceTreeUsable = false,
        )
        CaptureTrace.record(CaptureStage.CLASSIFIED, event.windowId, screenshotIdentityGeneration.get(), (if (regularCapture) 1 else 0) + (if (possibleEmptyTreeCapture) 2 else 0))
        val probeGeneration = screenshotIdentityGeneration.get()
        foregroundChatProbeDelays(event.eventType, event.className?.toString()).forEach { delayMillis ->
            CaptureTrace.record(CaptureStage.PROBE_SCHEDULED, event.windowId, probeGeneration, delayMillis.toInt())
            val traceWindow = event.windowId
            mainHandler.postDelayed({
                val current = screenshotIdentityGeneration.get() == probeGeneration
                CaptureTrace.record(if (current) CaptureStage.PROBE_RUN else CaptureStage.DELAY_CANCELLED, traceWindow, probeGeneration, delayMillis.toInt())
                if (current) captureCurrentForegroundViewport(packageName)
            }, delayMillis)
        }
        if (!regularCapture && !possibleEmptyTreeCapture) return
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            mainHandler.postDelayed(
                { captureCurrentForegroundViewport(packageName) },
                FOREGROUND_SEND_RENDER_DELAY_MILLIS,
            )
        }
        pendingFallbackRequest()?.let { request ->
            if (request.packageName == packageName) {
                scheduleFallback(request, "event:${fallbackRetryGeneration.incrementAndGet()}")
            }
        }
        val windowId = event.windowId
        val eventType = event.eventType
        val eventClass = event.className?.toString()
        val generation = snapshotGeneration.incrementAndGet()
        val identityGeneration = screenshotIdentityGeneration.get()
        // 系统会在回调返回后回收 event；只复制事件，不在键盘共用主线程读取页面树。
        @Suppress("DEPRECATION")
        val capturedEvent = AccessibilityEvent.obtain(event)
        eventReads.submit({
            !destroyed && snapshotGeneration.get() == generation && screenshotIdentityGeneration.get() == identityGeneration
        }) {
            if (packageName == WECHAT_PACKAGE || packageName == "com.ss.android.ugc.aweme") {
                com.yuyan.imemodule.data.capture.adapter.ChatCaptureSettings.refreshLocal(applicationContext)
            }
            if (!CollectionConsent.enabled(this@PassiveChatAccessibilityService) ||
                snapshotGeneration.get() != generation ||
                screenshotIdentityGeneration.get() != identityGeneration) {
                CaptureTrace.record(CaptureStage.SUPERSEDED, windowId, identityGeneration, eventType)
                return@submit
            }
            val activeRoot = rootInActiveWindow
            val activeSnapshot = try {
                // 排队期间已离开窗口时，不能用旧事件读取新 App/会话。
                val rootPackage = activeRoot?.packageName?.toString()?.takeIf { it.isNotBlank() }
                val rootWindow = activeRoot?.windowId ?: -1
                if ((rootPackage != null && rootPackage != packageName) ||
                    (rootWindow >= 0 && windowId >= 0 && rootWindow != windowId)) return@submit
                treeReader.read(activeRoot)
            } finally {
                activeRoot?.let(::recycleRoot)
            }
            // 完整 activeRoot 可用时不再跨进程读取一遍 event.source。
            val sourceSnapshot = if (!hasReadableChatContent(activeSnapshot)) {
                val source = capturedEvent.source
                try {
                    val sourcePackage = source?.packageName?.toString()?.takeIf { it.isNotBlank() }
                    if (sourcePackage == null || sourcePackage == packageName) treeReader.read(source) else null
                } finally {
                    if (source !== activeRoot) source?.let(::recycleRoot)
                }
            } else null
            // 新内容事件只替换尚未开始的读取，不能饿死已读出的首个视口。
            // 真正离开窗口/切身份仍使结果失效，实际截图前再复核当前会话。
            if (!CollectionConsent.enabled(this@PassiveChatAccessibilityService) ||
                screenshotIdentityGeneration.get() != identityGeneration || scrollGate.isScrolling()) return@submit
            CaptureTrace.record(CaptureStage.TREE, windowId, identityGeneration,
                (if (hasReadableChatContent(activeSnapshot)) 1 else 0) + (if (hasReadableChatContent(sourceSnapshot)) 2 else 0))
            if (!hasReadableChatContent(activeSnapshot) && !hasReadableChatContent(sourceSnapshot)) {
                val platform = if (packageName == WECHAT_PACKAGE) "wechat" else if (packageName == "com.ss.android.ugc.aweme") "douyin" else null
                platform?.let { com.yuyan.imemodule.data.capture.adapter.ChatCaptureDiagnostics.record(applicationContext, it, "page", "empty_tree") }
            }
            val snapshot = preferredAccessibilitySnapshot(activeSnapshot, sourceSnapshot)
            if (packageName == WECHAT_PACKAGE && snapshot != null && isWechatNonChatTree(snapshot)) return@submit
            if (shouldCaptureEmptyTreeWeChatOpen(
                    eventType = eventType,
                    className = eventClass,
                    visibleText = eventText,
                    activeTreeUsable = hasReadableChatContent(activeSnapshot),
                    sourceTreeUsable = hasReadableChatContent(sourceSnapshot),
                )
            ) {
                emptyTreeWeChatCaptureDelays(eventText).forEach { delayMillis ->
                    CaptureTrace.record(CaptureStage.TRANSCRIPT_SCHEDULED, windowId, identityGeneration, delayMillis.toInt(), eventText.contains("转文字"))
                    mainHandler.postDelayed({
                        val current = screenshotIdentityGeneration.get() == identityGeneration
                        CaptureTrace.record(if (current) CaptureStage.TRANSCRIPT_RUN else CaptureStage.DELAY_CANCELLED, windowId, identityGeneration, delayMillis.toInt())
                        if (current) captureEmptyTreeWeChatScreenshot()
                    }, delayMillis)
                }
                return@submit
            }
            if (packageName == WECHAT_PACKAGE && !hasReadableChatContent(activeSnapshot) &&
                !hasReadableChatContent(sourceSnapshot) && screenshotUpdates.accepts(windowId, identityGeneration, eventType)) {
                CaptureTrace.record(CaptureStage.CONTENT_SCHEDULED, windowId, identityGeneration)
                emptyUpdateDebouncer.submit(windowId, updateSequence.incrementAndGet().toString(),
                    ScreenshotScope(windowId, identityGeneration), "$windowId:$identityGeneration")
                return@submit
            }
            if (packageName == WECHAT_PACKAGE && !hasReadableChatContent(activeSnapshot) &&
                !hasReadableChatContent(sourceSnapshot) &&
                eventType in setOf(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, AccessibilityEvent.TYPE_VIEW_SCROLLED) &&
                screenshotGate.markChanged(ScreenshotScope(windowId, identityGeneration))) {
                // 初次确认期间的新内容先记账，确认成功后才补拍末次状态。
                CaptureTrace.record(CaptureStage.CONTENT_PENDING, windowId, identityGeneration)
                return@submit
            }
            if (packageName == WECHAT_PACKAGE && !hasReadableChatContent(activeSnapshot) &&
                !hasReadableChatContent(sourceSnapshot) && eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
                emptyCandidates.canAttempt(ScreenshotScope(windowId, identityGeneration))) {
                // 未确认首帧失败后，只允许有限候选；每一帧仍走窗口校验与当前图像身份确认。
                emptyUpdateDebouncer.submit(windowId, updateSequence.incrementAndGet().toString(),
                    ScreenshotScope(windowId, identityGeneration), "$windowId:$identityGeneration")
                return@submit
            }
            if (regularCapture && snapshot != null) submitChatViewport(packageName, windowId, snapshot, generation)
        }.invokeOnCompletion {
            // 即使协程尚未启动就被取消，也必须归还我们持有的事件副本。
            @Suppress("DEPRECATION")
            capturedEvent.recycle()
        }
    }

    override fun onInterrupt() {
        com.yuyan.imemodule.data.redpacket.GroupRedPacketAssistant.cancel("无障碍服务中断", false)
        navigationCapture?.reset()
        WechatExpressionConfirmation.cancel()
        resetScreenshotIdentity()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        com.yuyan.imemodule.data.collect.GameForegroundMonitor.connect(this)
        com.yuyan.imemodule.data.redpacket.GroupRedPacketAssistant.connect(this)
        navigationCapture?.close()
        navigationCapture = com.yuyan.imemodule.data.navigation.NavigationCapture(this)
        WechatExpressionConfirmation.connect(this)
        CaptureTrace.record(CaptureStage.CONNECTED)
        backgroundScope.launch {
            com.yuyan.imemodule.data.capture.adapter.ChatCaptureDiagnostics.bindDeviceId(DataCollector.deviceId(applicationContext))
            com.yuyan.imemodule.data.capture.adapter.ChatCaptureSettings.refresh(applicationContext)
        }
        val database = CaptureDatabase.create(applicationContext)
        val activeChatContextStore = ActiveChatContextStore(applicationContext)
        activeChatContextStore.clear()
        val activeMediaCapturer = WindowMediaCapturer(
            context = applicationContext,
            screenshotSource = WindowScreenshotter(this),
            captureAllowed = { CollectionConsent.enabled(applicationContext) && !scrollGate.isScrolling() },
            captureGeneration = captureRequestGeneration::get,
            onScreenshotResult = { platform, status, code -> com.yuyan.imemodule.data.capture.adapter.ChatCaptureDiagnostics.record(applicationContext, platform, "screenshot", status, code) },
        )
        captureDatabase = database
        mediaCapturer = activeMediaCapturer
        val identityStore = com.yuyan.imemodule.data.capture.media.PreferenceConversationIdentityStore(
            getSharedPreferences("chat-confirmed-identities", Context.MODE_PRIVATE),
        )
        screenshotIdentityResolver = MlKitWechatScreenshotIdentityResolver(identityStore)
        fallbackStore = NotificationScreenshotFallbackStore(applicationContext)
        coordinator = CaptureCoordinator(
            identityStore = identityStore,
            adapterForPackage = AdapterRegistry::forPackage,
            store = RoomCaptureOutboxStore(database.captureDao()),
            captureGeneration = captureRequestGeneration::get,
            deviceId = { DataCollector.deviceId(applicationContext) },
            wakeUploader = CaptureUploader::wake,
            captureAllowed = { CollectionConsent.enabled(applicationContext) && !scrollGate.isScrolling() },
            mediaCapturer = activeMediaCapturer,
            wechatListContentInput = { bounds ->
                val band = wechatTitleBand(systemStatusBarBottom(), bounds.top, resources.displayMetrics.density)
                ScreenshotContentInput(band.top + band.height, detectWechatList = true)
            },
            onPersistResult = { platform, result ->
                com.yuyan.imemodule.data.capture.adapter.ChatCaptureDiagnostics.record(applicationContext, platform.wireName, "persist",
                    when (result) { CapturePersistResult.INSERTED -> "inserted"; CapturePersistResult.ALREADY_PERSISTED -> "duplicate"; CapturePersistResult.FAILED -> "failed" })
                if (result == CapturePersistResult.INSERTED) com.yuyan.imemodule.data.capture.adapter.ChatCaptureDiagnostics.record(applicationContext, platform.wireName, "upload", "waiting")
            },
            onViewportParsed = { viewport ->
                val incoming = viewport.messages.lastOrNull { it.direction == ChatDirection.INCOMING && !it.text.isNullOrBlank() }
                if (incoming == null) {
                    activeChatContextStore.clear()
                } else {
                    val conversation = viewport.conversation
                    conversation.externalKey?.let { externalKey ->
                        activeChatContextStore.save(ActiveChatContext(
                            platform = conversation.platform,
                            accountKey = conversation.accountKey,
                            externalKey = externalKey,
                            displayName = conversation.displayName,
                            conversationType = conversation.conversationType,
                            latestIncomingText = incoming.text.orEmpty(),
                            observedAt = System.currentTimeMillis(),
                        ))
                    }
                }
            },
        )
        fallbackConnection = NotificationScreenshotFallbackBridge.connect { request ->
            fallbackStore?.offer(request)
            fallbackQueue.offer(request)
            scheduleFallback(request, "notification:${request.notificationKey}:${request.postedAtMillis}")
        }
        foregroundCaptureConnection = ForegroundChatCaptureBridge.connect { request ->
            mainHandler.postDelayed(
                { captureCurrentForegroundViewport(request.packageName) },
                FOREGROUND_SEND_RENDER_DELAY_MILLIS,
            )
        }
        pendingFallbackRequest()?.let { request ->
            scheduleFallback(request, "service-connected:${request.notificationKey}:${request.postedAtMillis}")
        }
    }

    override fun onDestroy() {
        com.yuyan.imemodule.data.collect.GameForegroundMonitor.disconnect(this)
        com.yuyan.imemodule.data.redpacket.GroupRedPacketAssistant.disconnect(this)
        navigationCapture?.close()
        navigationCapture = null
        WechatExpressionConfirmation.disconnect(this)
        destroyed = true
        resetScreenshotIdentity()
        snapshotGeneration.incrementAndGet()
        debouncer.close()
        fallbackDebouncer.close()
        fallbackConnection?.cancel()
        fallbackConnection = null
        foregroundCaptureConnection?.cancel()
        foregroundCaptureConnection = null
        fallbackStore = null
        backgroundScope.cancel()
        backgroundDispatcher.close()
        captureDatabase?.close()
        captureDatabase = null
        coordinator = null
        mediaCapturer = null
        (screenshotIdentityResolver as? java.io.Closeable)?.close()
        screenshotIdentityResolver = null
        super.onDestroy()
    }

    private fun submitChatViewport(packageName: String, windowId: Int, snapshot: UiNodeSnapshot, generation: Long, confirmationAttempt: Int = 0) {
        val adapter = AdapterRegistry.forPackage(packageName)
        val result = if (adapter is DouyinChatAdapter) {
            adapter.inspect(snapshot).also { douyinDiagnostics.record(it.status) }.result
        } else adapter?.parse(snapshot)
        val parsed = result as? ParseResult.Success
        val diagnosticPlatform = if (packageName == WECHAT_PACKAGE) "wechat" else if (packageName == "com.ss.android.ugc.aweme") "douyin" else null
        diagnosticPlatform?.let { com.yuyan.imemodule.data.capture.adapter.ChatCaptureDiagnostics.record(applicationContext, it, "page",
            if (!hasReadableChatContent(snapshot)) "empty_tree" else if (parsed != null) "matched" else "rejected") }
        val conversation = parsed?.viewport?.conversation
        val key = conversation?.stableKeyOrNull()
        if (key == null || conversation.identityConfidence < 0.8) {
            CaptureTrace.record(CaptureStage.PAGE_REJECTED, windowId, screenshotIdentityGeneration.get(), flag = parsed != null)
            coordinator?.resetConversationIdentity()
            debouncer.close()
            return
        }
        CaptureTrace.record(CaptureStage.VIEWPORT_READY, windowId, screenshotIdentityGeneration.get())
        debouncer.submit(
            windowId,
            viewportCaptureSignature(packageName, snapshot.stableTreeSignature(), generation, confirmationAttempt),
            StableViewport(packageName, windowId, snapshot, confirmationAttempt),
            contextKey = key,
        )
    }

    private fun onStableViewport(viewport: StableViewport) {
        val identityGeneration = screenshotIdentityGeneration.get()
        // 限频等待期间可能已切换会话或退出 App；后台复核当前树，不阻塞输入法主线程。
        stableReads.submit({ !destroyed && screenshotIdentityGeneration.get() == identityGeneration }) {
            if (!CollectionConsent.enabled(this@PassiveChatAccessibilityService) ||
                screenshotIdentityGeneration.get() != identityGeneration || scrollGate.isScrolling()) return@submit
            val root = rootInActiveWindow ?: return@submit
            val current = try {
                if (root.packageName?.toString() != viewport.packageName || root.windowId != viewport.windowId) {
                    return@submit
                }
                treeReader.read(root)
            } finally {
                recycleRoot(root)
            } ?: return@submit
            if (!samePendingChat(viewport.packageName, viewport.snapshot, current)) {
                return@submit
            }
            val activeCoordinator = coordinator ?: return@submit
            if (!CollectionConsent.enabled(this@PassiveChatAccessibilityService) ||
                screenshotIdentityGeneration.get() != identityGeneration || scrollGate.isScrolling()) return@submit
            val pendingName = activeCoordinator.capture(viewport.packageName, current, viewport.windowId)
            if (pendingName && viewport.confirmationAttempt < 2) {
                mainHandler.postDelayed({
                    if (screenshotIdentityGeneration.get() == identityGeneration) {
                        captureCurrentForegroundViewport(viewport.packageName, viewport.confirmationAttempt + 1)
                    }
                }, 800)
            }
        }
    }

    private fun captureCurrentForegroundViewport(expectedPackage: String, confirmationAttempt: Int = 0, scrollResumeOnly: Boolean = false) {
        if (!CollectionConsent.enabled(this) || !isForegroundChatCapturePackage(expectedPackage)) return
        val generation = snapshotGeneration.get()
        val identityGeneration = screenshotIdentityGeneration.get()
        foregroundReads.submit({ !destroyed && screenshotIdentityGeneration.get() == identityGeneration }) {
            if (expectedPackage == WECHAT_PACKAGE || expectedPackage == "com.ss.android.ugc.aweme") {
                com.yuyan.imemodule.data.capture.adapter.ChatCaptureSettings.refreshLocal(applicationContext)
            }
            if (!CollectionConsent.enabled(this@PassiveChatAccessibilityService) ||
                screenshotIdentityGeneration.get() != identityGeneration) {
                return@submit
            }
            val root = rootInActiveWindow ?: return@submit
            try {
                val packageName = root.packageName?.toString()
                if (packageName != expectedPackage) {
                    return@submit
                }
                val scrollScope = ScreenshotScope(root.windowId, identityGeneration)
                val snapshot = treeReader.read(root)
                if (packageName == WECHAT_PACKAGE && snapshot != null && isWechatNonChatTree(snapshot)) return@submit
                CaptureTrace.record(CaptureStage.TREE, root.windowId, identityGeneration, value = if (hasReadableChatContent(snapshot)) 1 else 0, flag = true)
                if (packageName == WECHAT_PACKAGE && !hasReadableChatContent(snapshot)) {
                    if (!scrollResumeOnly) screenshotUpdates.allowScrollResume(scrollScope)
                    if (scrollGate.isScrolling()) return@submit
                    if (scrollResumeOnly && !screenshotUpdates.canResumeScroll(scrollScope)) return@submit
                    mainHandler.post {
                        if (screenshotIdentityGeneration.get() == identityGeneration) captureEmptyTreeWeChatScreenshot(scrollResumeOnly)
                    }
                    return@submit
                }
                if (snapshot == null || scrollGate.isScrolling()) return@submit
                val windowId = root.windowId
                if (!CollectionConsent.enabled(this@PassiveChatAccessibilityService) ||
                    screenshotIdentityGeneration.get() != identityGeneration || scrollGate.isScrolling()) return@submit
                submitChatViewport(packageName, windowId, snapshot, generation, confirmationAttempt)
            } finally {
                recycleRoot(root)
            }
        }
    }

    private fun isScreenshotRequestCurrent(generation: Long, captureToken: Long): Boolean =
        !destroyed && CollectionConsent.enabled(this) &&
            screenshotIdentityGeneration.get() == generation && captureRequestGeneration.get() == captureToken

    private suspend fun isCurrentScreenshotWindow(windowId: Int, generation: Long, captureToken: Long): Boolean =
        withContext(Dispatchers.IO) {
            if (!ImageUploadRuntime.isBackgroundWorkAllowed() || !isScreenshotRequestCurrent(generation, captureToken) || scrollGate.isScrolling() ||
                (getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked == true) return@withContext false
            val root = rootInActiveWindow
            val current = if (root == null) readScreenshotWindow()?.id == windowId else {
                try { root.packageName?.toString() == WECHAT_PACKAGE && root.windowId == windowId }
                finally { recycleRoot(root) }
            }
            current && isScreenshotRequestCurrent(generation, captureToken) && !scrollGate.isScrolling()
        }

    private fun captureScreenshotInScope(scope: ScreenshotScope) {
        if (destroyed || screenshotIdentityGeneration.get() != scope.generation) return
        requestEmptyTreeScreenshot(scrollResumeOnly = false, expectedScope = scope)
    }

    private fun captureEmptyTreeWeChatScreenshot(scrollResumeOnly: Boolean = false) {
        requestEmptyTreeScreenshot(scrollResumeOnly, expectedScope = null)
    }

    private data class ScreenshotWindowSnapshot(val id: Int, val bounds: IntRect, val inputMethodTop: Int?)

    private fun readScreenshotWindow(): ScreenshotWindowSnapshot? {
        val visibleWindows = windows
        val targetWindow = visibleWindows.firstOrNull { window ->
            window.type == AccessibilityWindowInfo.TYPE_APPLICATION && window.isActive
        } ?: return null
        val root = targetWindow.root
        val packageName = try { root?.packageName?.toString() } finally { root?.let(::recycleRoot) }
        if (packageName != WECHAT_PACKAGE) return null
        val windowRect = Rect().also(targetWindow::getBoundsInScreen)
        val displayMetrics = resources.displayMetrics
        val bounds = if (windowRect.width() > 0 && windowRect.height() > 0) {
            IntRect(windowRect.left, windowRect.top, windowRect.right, windowRect.bottom)
        } else IntRect(0, 0, displayMetrics.widthPixels, displayMetrics.heightPixels)
        val inputMethodTop = visibleWindows.asSequence()
            .filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            .map { window -> Rect().also(window::getBoundsInScreen).top }
            .filter { it > bounds.top }
            .minOrNull()
        return ScreenshotWindowSnapshot(targetWindow.id, bounds, inputMethodTop)
    }

    private fun requestEmptyTreeScreenshot(scrollResumeOnly: Boolean, expectedScope: ScreenshotScope?) {
        val generation = screenshotIdentityGeneration.get()
        val captureToken = captureRequestGeneration.get()
        CaptureTrace.record(CaptureStage.EMPTY_REQUEST, generation = generation)
        if (!isScreenshotRequestCurrent(generation, captureToken)) return
        screenshotReads.submit({ isScreenshotRequestCurrent(generation, captureToken) }) {
            if (!isScreenshotRequestCurrent(generation, captureToken)) return@submit
            // Binder 等待在后台完成，主线程仅处理轻量调度状态。
            val snapshot = readScreenshotWindow() ?: run {
                com.yuyan.imemodule.data.capture.adapter.ChatCaptureDiagnostics.record(applicationContext, "wechat", "screenshot", "cancelled", -1001)
                return@submit
            }
            val capture = withContext(Dispatchers.Main) {
                if (!isScreenshotRequestCurrent(generation, captureToken) ||
                    (expectedScope != null && expectedScope != ScreenshotScope(snapshot.id, generation))) return@withContext null
                startEmptyTreeScreenshot(snapshot, generation, captureToken, scrollResumeOnly)
            }
            capture?.join()
        }
    }

    private fun startEmptyTreeScreenshot(
        snapshot: ScreenshotWindowSnapshot,
        identityGeneration: Long,
        captureToken: Long,
        scrollResumeOnly: Boolean,
    ): Job? {
        val windowId = snapshot.id
        val resumeScope = ScreenshotScope(windowId, identityGeneration)
        val typingDelay = screenshotUpdates.captureDelayMillis(resumeScope)
        if (typingDelay > 0) {
            // 动画事件合并成一次末次检查；不重置计时，避免标题恢复后永远被延后。
            if (typingRecheck == null) {
                typingRecheck = Runnable {
                    typingRecheck = null
                    captureScreenshotInScope(resumeScope)
                }.also { mainHandler.postDelayed(it, typingDelay) }
            }
            return null
        }
        typingRecheck?.let(mainHandler::removeCallbacks)
        typingRecheck = null
        if (scrollResumeOnly) {
            if (!screenshotUpdates.canResumeScroll(resumeScope)) return null
        } else screenshotUpdates.allowScrollResume(resumeScope)
        if (scrollGate.isScrolling()) return null

        val displayMetrics = resources.displayMetrics
        val windowBounds = snapshot.bounds
        val statusBarBottom = systemStatusBarBottom()
        val screenshotBounds = emptyTreeScreenshotBounds(windowBounds, snapshot.inputMethodTop, statusBarBottom)
        if (screenshotBounds.right <= screenshotBounds.left || screenshotBounds.bottom - screenshotBounds.top < 400) {
            return null
        }

        // 截图已排除系统状态栏，标题带使用相对截图原点，不能再次裁掉标题。
        val titleBand = wechatTitleBand(statusBarBottom, screenshotBounds.top, displayMetrics.density)

        val screenshotScope = ScreenshotScope(windowId, identityGeneration)
        val confirmed = screenshotUpdates.accepts(windowId, identityGeneration, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        if (!confirmed && !emptyCandidates.canAttempt(screenshotScope)) return null
        if (!screenshotGate.offer(screenshotScope)) return null
        if (!confirmed) emptyCandidates.consume(screenshotScope)
        val capture = backgroundScope.launch {
            emptyTreeCaptureMutex.withLock {
                TitleOcrInput(titleBand.top, titleBand.height).use { titleInput ->
                    if (!isCurrentScreenshotWindow(windowId, identityGeneration, captureToken)) return@withLock
                    val resolver = screenshotIdentityResolver
                    val resolverVersion = resolver?.version() ?: 0L
                    val contentInput = ScreenshotContentInput(titleBand.top + titleBand.height, detectWechatList = true, detectBlocks = true, detectWechatBody = true)
                    val asset = mediaCapturer?.capture(
                        windowId = windowId,
                        windowBounds = windowBounds,
                        requests = listOf(MediaCaptureRequest(0, screenshotBounds, lossyWebp = true, titleOcrInput = titleInput, wechatInputBarDensity = displayMetrics.density, contentInput = contentInput, platform = ChatPlatform.WECHAT)),
                    )?.get(0) ?: run {
                        CaptureTrace.record(CaptureStage.ASSET_FAILED, windowId, identityGeneration)
                        return@withLock
                    }
                    if (!isCurrentScreenshotWindow(windowId, identityGeneration, captureToken)) return@withLock
                    CaptureTrace.record(CaptureStage.ASSET_READY, windowId, identityGeneration)
                    val preferences = getSharedPreferences(FALLBACK_PREFERENCES, Context.MODE_PRIVATE)
                    // 列表候选使用原始像素指纹；有损编码相同不能证明细小正文变化不存在。
                    if (contentInput.sha256 == null && contentInput.wechatListSha256 == null && screenshotUpdates.hasSavedContent(screenshotScope) &&
                        preferences.getString(LAST_EMPTY_TREE_SCREENSHOT_SHA, null) == asset.sha256 &&
                        isReadableScreenshotTitleStatus(preferences.getString("last_title_identity_status", null))) {
                        CaptureTrace.record(CaptureStage.DUPLICATE, windowId, identityGeneration)
                        return@withLock
                    }
                    val capturedAt = System.currentTimeMillis()
                    val screenshotCaptureId = java.util.UUID.randomUUID().toString()
                    val firstIdentity = resolver?.resolve(asset, resolverVersion, titleInput) ?: unresolvedWechatScreenshotIdentity()
                    if (!isCurrentScreenshotWindow(windowId, identityGeneration, captureToken)) return@withLock
                    CaptureTrace.record(if (firstIdentity.isChatPage) CaptureStage.IDENTITY_READY else CaptureStage.IDENTITY_REJECTED,
                        windowId, identityGeneration, flag = firstIdentity.status == "confirmed")
                    com.yuyan.imemodule.data.capture.adapter.ChatCaptureDiagnostics.record(applicationContext, "wechat", "page", if (firstIdentity.isChatPage) "matched" else "rejected")
                    if (!firstIdentity.isChatPage) {
                        screenshotUpdates.rejectScrollResume(screenshotScope)
                        return@withLock
                    }
                    if (isPeerTypingConversationTitle(firstIdentity.observedTitle) || isPeerTypingConversationTitle(firstIdentity.displayName)) {
                        // 首次进入就遇到输入状态时仍等后续标题恢复，但本帧不保存、不确认重放。
                        screenshotUpdates.observeTitle(windowId, identityGeneration, "typing")
                        return@withLock
                    }
                    if (isReadableScreenshotTitleStatus(firstIdentity.status) && screenshotUpdates.isSavedContent(
                            screenshotScope, firstIdentity.externalKey, firstIdentity.exactTitleHash, contentInput.sha256)) {
                        CaptureTrace.record(CaptureStage.CONTENT_DUPLICATE, windowId, identityGeneration)
                        return@withLock
                    }
                    val listMetadata = wechatListMetadata(firstIdentity.isWechatConversationList(), contentInput.wechatListSha256)
                    suspend fun persist(identity: ScreenshotConversationIdentity): CapturePersistResult {
                        if (!isCurrentScreenshotWindow(windowId, identityGeneration, captureToken)) return CapturePersistResult.FAILED
                        screenshotUpdates.observeTitle(windowId, identityGeneration, identity.status)
                        val result = coordinator?.captureParsed(
                            conversation = CapturedConversation(
                                platform = ChatPlatform.WECHAT,
                                accountKey = "wechat-empty-tree",
                                externalKey = identity.externalKey,
                                displayName = identity.displayName,
                                conversationType = firstIdentity.conversationType,
                                identityConfidence = identity.confidence,
                            ),
                            messages = listOf(CapturedMessage(
                                conversationKey = null,
                                senderKey = "${identity.externalKey}:viewport",
                                direction = ChatDirection.SYSTEM,
                                messageType = ChatMessageType.IMAGE,
                                occurredAt = isoTimestamp(capturedAt),
                                metadata = mapOf(
                                    "capture_source" to "wechat_empty_tree_screenshot",
                                    "screenshot_capture_id" to screenshotCaptureId,
                                    "identity_unavailable" to (identity.status != "confirmed").toString(),
                                    "conversation_identity_source" to identity.source,
                                    "conversation_identity_status" to identity.status,
                                    "conversation_identity_observed_title" to identity.observedTitle.orEmpty(),
                                    "conversation_identity_previous_key" to identity.previousKey.orEmpty(),
                                ) + listMetadata,
                            )),
                            pendingAssetsByMessage = mapOf(0 to asset),
                            captureToken = captureToken,
                            screenshotContentByMessage = if (identity === firstIdentity) firstIdentity.confirmedContent(contentInput) else emptyMap(),
                            screenshotPixelHashes = contentInput.sha256?.let { mapOf(0 to it) }.orEmpty(),
                        ) ?: CapturePersistResult.FAILED
                        CaptureTrace.record(CaptureStage.PERSIST_RESULT, windowId, identityGeneration, result.ordinal)
                        if (result != CapturePersistResult.FAILED) {
                            screenshotUpdates.recordSavedContent(screenshotScope, firstIdentity.externalKey,
                                firstIdentity.exactTitleHash, contentInput.sha256, result,
                                sameFrameConfirmed = identity === firstIdentity && isReadableScreenshotTitleStatus(firstIdentity.status))
                            preferences.edit().putString(LAST_EMPTY_TREE_SCREENSHOT_SHA, asset.sha256)
                                .putString("last_title_identity_status", identity.status).apply()
                        }

                        return result
                    }
                    persistScreenshotBeforeConfirmation(firstIdentity, ::persist) {
                        delay(800)
                        if (!isCurrentScreenshotWindow(windowId, identityGeneration, captureToken)) return@persistScreenshotBeforeConfirmation null
                        TitleOcrInput(titleBand.top, titleBand.height).use { confirmationInput ->
                            val nextAsset = mediaCapturer?.capture(
                                windowId = windowId,
                                windowBounds = windowBounds,
                                requests = listOf(MediaCaptureRequest(0, screenshotBounds, lossyWebp = true, titleOcrInput = confirmationInput, wechatInputBarDensity = displayMetrics.density, platform = ChatPlatform.WECHAT)),
                            )?.get(0) ?: return@persistScreenshotBeforeConfirmation null
                            if (!isCurrentScreenshotWindow(windowId, identityGeneration, captureToken)) return@persistScreenshotBeforeConfirmation null
                            resolver?.resolve(nextAsset, resolverVersion, confirmationInput)?.takeIf { it.isChatPage }
                        }
                    }
                }
            }
        }
        capture.invokeOnCompletion {
            val confirmedScope = ScreenshotScope(windowId, identityGeneration).takeIf {
                screenshotUpdates.accepts(it.window, it.generation, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
            }
            screenshotGate.complete(confirmedScope)?.let { scope ->
                mainHandler.post { captureScreenshotInScope(scope) }
            }
        }
        return capture
    }

    private fun systemStatusBarBottom(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id != 0) resources.getDimensionPixelSize(id) else 0
    }

    private fun onStableFallbackRequest(request: NotificationScreenshotFallbackRequest) {
        fallbackReads.submit({ !destroyed && pendingFallbackRequest() == request }) {
            fallbackCaptureMutex.withLock {
                if (!ImageUploadRuntime.isBackgroundWorkAllowed()) {
                    scheduleFallbackRetry(request)
                    return@withLock
                }
                if (!CollectionConsent.enabled(this@PassiveChatAccessibilityService)) return@withLock
                if (pendingFallbackRequest() != request) return@withLock
                val descriptor = pendingNotificationScreenshotDescriptor(request) ?: run {
                    completeFallback(request)
                    return@withLock
                }
                val captureToken = captureRequestGeneration.get()
                val target = currentFallbackTarget(request) ?: return@withLock
                val resolverVersion = screenshotIdentityResolver?.version()
                val screenshotBounds = target.chatViewport?.bounds ?: notificationFallbackBounds(target.windowBounds, systemStatusBarBottom())
                val wechatEmptyTree = request.packageName == WECHAT_PACKAGE && target.chatViewport == null
                val band = wechatTitleBand(systemStatusBarBottom(), screenshotBounds.top, resources.displayMetrics.density)
                val titleInput = if (wechatEmptyTree) TitleOcrInput(band.top, band.height) else null
                val contentInput = if (wechatEmptyTree || target.chatViewport?.isWechatConversationList == true)
                    ScreenshotContentInput(band.top + band.height, detectWechatList = true, detectBlocks = wechatEmptyTree, detectWechatBody = wechatEmptyTree) else ScreenshotContentInput(0)
                val (asset, frameIdentity) = titleInput.use { input ->
                    val captured = mediaCapturer?.capture(
                        windowId = target.windowId,
                        windowBounds = target.windowBounds,
                        requests = listOf(
                            MediaCaptureRequest(
                                messageIndex = 0,
                                platform = descriptor.platform,
                                bounds = screenshotBounds,
                                inputAreaBounds = target.chatViewport?.inputAreaBounds,
                                lossyWebp = true,
                                titleOcrInput = input,
                                wechatInputBarDensity = resources.displayMetrics.density.takeIf { wechatEmptyTree },
                                contentInput = contentInput,
                            ),
                        ),
                    )?.get(0)
                    // 仅识别原始标题栏，不能把顶部正文当成输入状态，也不借“待确认截图”绕过过滤。
                    val title = if (captured != null && input != null) {
                        screenshotIdentityResolver?.resolve(captured, resolverVersion ?: 0L, input)
                    } else null
                    captured to title
                }
                if (asset == null) {
                    scheduleFallbackRetry(request)
                    return@withLock
                }
                val observedTitle = frameIdentity?.observedTitle
                if (frameIdentity?.isChatPage == false || isPeerTypingConversationTitle(observedTitle) ||
                    isPeerTypingConversationTitle(frameIdentity?.displayName)) {
                    scheduleFallbackRetry(request)
                    return@withLock
                }
                val targetAfterCapture = currentFallbackTarget(request)
                if (captureRequestGeneration.get() != captureToken || targetAfterCapture?.windowId != target.windowId ||
                    targetAfterCapture?.chatViewport != target.chatViewport || pendingFallbackRequest() != request) {
                    return@withLock
                }
                val preferences = getSharedPreferences(FALLBACK_PREFERENCES, Context.MODE_PRIVATE)
                val screenshotShaKey = if (request.packageName == WECHAT_PACKAGE) {
                    LAST_SCREENSHOT_SHA
                } else {
                    "$LAST_SCREENSHOT_SHA:${request.packageName}"
                }
                if (contentInput.sha256 == null && contentInput.wechatListSha256 == null && preferences.getString(screenshotShaKey, null) == asset.sha256 &&
                    preferences.getString("$screenshotShaKey:identity", null) == descriptor.externalKey) {
                    completeFallback(request)
                    return@withLock
                }

                val listMetadata = wechatListMetadata(target.chatViewport?.isWechatConversationList == true ||
                    frameIdentity?.isWechatConversationList() == true, contentInput?.wechatListSha256)
                val persistResult = coordinator?.captureParsed(
                    conversation = if (listMetadata.isNotEmpty()) wechatListConversation else CapturedConversation(
                        platform = descriptor.platform,
                        accountKey = "notification-screenshot",
                        externalKey = descriptor.externalKey,
                        displayName = descriptor.displayName,
                        conversationType = ConversationType.UNKNOWN,
                        identityConfidence = FALLBACK_IDENTITY_CONFIDENCE,
                    ),
                    messages = listOf(
                        CapturedMessage(
                            conversationKey = null,
                            senderKey = "notification-screenshot:unknown",
                            direction = ChatDirection.INCOMING,
                            messageType = ChatMessageType.IMAGE,
                            text = descriptor.messageText,
                            displayedTime = isoTimestamp(request.postedAtMillis),
                            occurredAt = isoTimestamp(request.postedAtMillis),
                            metadata = mapOf(
                                "capture_source" to "notification_screenshot_fallback",
                                "conversation_identity_status" to "pending",
                                "conversation_identity_source" to "notification_screenshot_unverified",
                                "conversation_identity_observed_title" to observedTitle.orEmpty(),
                                "notification_key" to request.notificationKey,
                                "source_package" to request.packageName,
                                "identity_unavailable" to listMetadata.isEmpty().toString(),
                            ) + listMetadata,
                        ),
                    ),
                    pendingAssetsByMessage = mapOf(0 to asset),
                    captureToken = captureToken,
                    screenshotContentByMessage = frameIdentity?.confirmedContent(contentInput).orEmpty(),
                    screenshotPixelHashes = contentInput.sha256?.let { mapOf(0 to it) }.orEmpty(),
                ) ?: CapturePersistResult.FAILED
                if (persistResult != CapturePersistResult.FAILED) {
                    preferences.edit().putString(screenshotShaKey, asset.sha256)
                        .putString("$screenshotShaKey:identity", descriptor.externalKey).apply()
                    completeFallback(request)
                } else {
                    scheduleFallbackRetry(request)
                }
            }
        }
    }

    private fun pendingFallbackRequest(): NotificationScreenshotFallbackRequest? {
        fallbackQueue.peek()?.let { return it }
        return fallbackStore?.load()?.also(fallbackQueue::offer)
    }

    private fun completeFallback(request: NotificationScreenshotFallbackRequest) {
        fallbackQueue.removeIfSame(request)
        fallbackStore?.removeIfSame(request)
        pendingFallbackRequest()?.let { next ->
            scheduleFallback(next, "next:${next.notificationKey}:${next.postedAtMillis}")
        }
    }

    private fun scheduleFallbackRetry(request: NotificationScreenshotFallbackRequest) {
        backgroundScope.launch {
            delay(FALLBACK_RETRY_MILLIS)
            if (pendingFallbackRequest() == request) {
                scheduleFallback(request, "retry:${fallbackRetryGeneration.incrementAndGet()}")
            }
        }
    }

    private fun scheduleFallback(request: NotificationScreenshotFallbackRequest, signature: String) {
        fallbackDebouncer.submit(
            windowId = FALLBACK_DEBOUNCE_WINDOW_ID,
            signature = signature,
            value = request,
        )
    }

    private suspend fun currentFallbackTarget(
        request: NotificationScreenshotFallbackRequest,
    ): FallbackTarget? = withContext(backgroundDispatcher) {
        if (!ImageUploadRuntime.isBackgroundWorkAllowed() || destroyed || scrollGate.isScrolling() ||
            pendingFallbackRequest() != request) return@withContext null
        val root = rootInActiveWindow ?: return@withContext null
        try {
            val inputMethodVisible = windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            val screenLocked = (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceLocked
            if (!shouldCaptureNotificationFallback(
                    screenLocked = screenLocked,
                    foregroundPackage = root.packageName?.toString(),
                    inputMethodVisible = inputMethodVisible,
                    targetPackage = request.packageName,
                )) return@withContext null
            val snapshot = treeReader.read(root)
            if (request.packageName == WECHAT_PACKAGE && snapshot != null && isWechatNonChatTree(snapshot)) return@withContext null
            val parsed = snapshot?.let { AdapterRegistry.forPackage(request.packageName)?.parse(it) } as? ParseResult.Success
            if (isPeerTypingConversationTitle(parsed?.viewport?.conversation?.displayName)) return@withContext null
            val chatViewport = snapshot?.let { notificationChatViewport(request.packageName, it) }
            // QQ/抖音必须确认聊天页，微信空树沿用已有补偿路径。
            if (chatViewport == null && request.packageName != WECHAT_PACKAGE) return@withContext null
            if (!ImageUploadRuntime.isBackgroundWorkAllowed() || pendingFallbackRequest() != request) return@withContext null
            val bounds = Rect()
            root.getBoundsInScreen(bounds)
            FallbackTarget(
                windowId = root.windowId,
                windowBounds = IntRect(bounds.left, bounds.top, bounds.right, bounds.bottom),
                chatViewport = chatViewport,
            ).takeIf { bounds.width() > 0 && bounds.height() > 0 }
        } finally { recycleRoot(root) }
    }

    private fun isoTimestamp(milliseconds: Long): String = java.text.SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        java.util.Locale.US,
    ).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(milliseconds))

    @Suppress("DEPRECATION")
    private fun recycleRoot(root: android.view.accessibility.AccessibilityNodeInfo) = root.recycle()

    private data class StableViewport(
        val packageName: String,
        val windowId: Int,
        val snapshot: UiNodeSnapshot,
        val confirmationAttempt: Int = 0,
    )

    private data class FallbackTarget(
        val windowId: Int,
        val windowBounds: IntRect,
        val chatViewport: NotificationChatViewport?,
    )

    private companion object {
        val SUPPORTED_PACKAGES = ACCESSIBILITY_CHAT_EVENT_PACKAGES
        const val FOREGROUND_SEND_RENDER_DELAY_MILLIS = 700L
        const val FALLBACK_DELAY_MILLIS = 1_200L
        const val FALLBACK_RETRY_MILLIS = 5_000L
        const val FALLBACK_DEBOUNCE_WINDOW_ID = -1
        const val FALLBACK_IDENTITY_CONFIDENCE = 0.55
        const val FALLBACK_PREFERENCES = "notification_screenshot_fallback"
        const val LAST_SCREENSHOT_SHA = "last_screenshot_sha256"
        const val LAST_EMPTY_TREE_SCREENSHOT_SHA = "last_empty_tree_screenshot_sha256"
    }
}

internal fun emptyTreeScreenshotBounds(windowBounds: IntRect, inputMethodTop: Int?, statusBarBottom: Int = 0): IntRect {
    val bottom = inputMethodTop?.takeIf { it > windowBounds.top && it < windowBounds.bottom } ?: windowBounds.bottom
    val top = statusBarBottom.takeIf { it > windowBounds.top && it < bottom } ?: windowBounds.top
    // 只裁系统明确的边界；微信输入栏交给后续视觉适配，不按百分比截断末条消息。
    return IntRect(windowBounds.left, top, windowBounds.right, bottom)
}

internal fun viewportCaptureSignature(packageName: String, treeSignature: String, eventGeneration: Long, confirmationAttempt: Int = 0): String =
    if (isForegroundChatCapturePackage(packageName))
        "$treeSignature:$eventGeneration" +
            if (packageName != "com.tencent.mobileqq" && confirmationAttempt > 0) ":confirm:$confirmationAttempt" else ""
    else treeSignature

internal fun samePendingChat(packageName: String, previous: UiNodeSnapshot, current: UiNodeSnapshot): Boolean {
    val adapter = AdapterRegistry.forPackage(packageName) ?: return false
    val before = (adapter.parse(previous) as? ParseResult.Success)?.viewport?.conversation ?: return false
    val after = (adapter.parse(current) as? ParseResult.Success)?.viewport?.conversation ?: return false
    val key = before.stableKeyOrNull() ?: return false
    // 调用方已校验导航代次与窗口；标题暂时变为输入状态不等于换联系人。
    val typingTransition = packageName == "com.tencent.mm" &&
        !com.yuyan.imemodule.data.capture.media.isTransientConversationTitle(before.displayName) &&
        com.yuyan.imemodule.data.capture.media.isTransientConversationTitle(after.displayName)
    return before.identityConfidence >= 0.8 && after.identityConfidence >= 0.8 &&
        (key == after.stableKeyOrNull() || typingTransition)
}
