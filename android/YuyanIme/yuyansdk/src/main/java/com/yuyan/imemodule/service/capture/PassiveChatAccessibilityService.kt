package com.yuyan.imemodule.service.capture

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
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
import com.yuyan.imemodule.data.capture.db.CaptureDatabase
import com.yuyan.imemodule.data.capture.media.WindowMediaCapturer
import com.yuyan.imemodule.data.capture.media.WindowScreenshotter
import com.yuyan.imemodule.data.capture.media.MediaCaptureRequest
import com.yuyan.imemodule.data.capture.media.MlKitWechatScreenshotIdentityResolver
import com.yuyan.imemodule.data.capture.media.ScreenshotConversationIdentityResolver
import com.yuyan.imemodule.data.capture.media.unresolvedWechatScreenshotIdentity
import com.yuyan.imemodule.data.capture.media.persistScreenshotBeforeConfirmation
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
import kotlin.coroutines.resume
import com.yuyan.imemodule.data.capture.ActiveChatContext
import com.yuyan.imemodule.data.capture.ActiveChatContextStore
import com.yuyan.imemodule.data.capture.model.ChatDirection
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 只读被动采集入口。仅解析稳定视口，并按需截取窗口中的媒体区域，不操作 UI。
 */
class PassiveChatAccessibilityService : AccessibilityService() {
    private val backgroundDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val backgroundScope = CoroutineScope(SupervisorJob() + backgroundDispatcher)
    private val treeReader = AccessibilityTreeReader()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val snapshotGeneration = AtomicLong(0)
    private val fallbackRetryGeneration = AtomicLong(0)
    private val fallbackQueue = NotificationScreenshotFallbackQueue()
    private val fallbackCaptureMutex = Mutex()
    private val emptyTreeCaptureMutex = Mutex()
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
        captureRequestGeneration.incrementAndGet()
        screenshotIdentityGeneration.incrementAndGet()
        screenshotIdentityResolver?.reset()
        coordinator?.resetConversationIdentity()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
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
        if (shouldResetScreenshotIdentity(packageName, event.eventType, eventText)) resetScreenshotIdentity()
        val regularCapture = shouldCaptureForegroundChatEvent(event.eventType, event.className?.toString(), eventText)
        val possibleEmptyTreeCapture = shouldCaptureEmptyTreeWeChatOpen(
            eventType = event.eventType,
            className = event.className?.toString(),
            visibleText = eventText,
            activeTreeUsable = false,
            sourceTreeUsable = false,
        )
        val probeGeneration = screenshotIdentityGeneration.get()
        foregroundChatProbeDelays(event.eventType, event.className?.toString()).forEach { delayMillis ->
            mainHandler.postDelayed({
                if (screenshotIdentityGeneration.get() == probeGeneration) captureCurrentForegroundViewport(packageName)
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
        backgroundScope.launch {
            if (!CollectionConsent.enabled(this@PassiveChatAccessibilityService) ||
                snapshotGeneration.get() != generation ||
                screenshotIdentityGeneration.get() != identityGeneration) return@launch
            val activeRoot = rootInActiveWindow
            val activeSnapshot = try {
                // 排队期间已离开窗口时，不能用旧事件读取新 App/会话。
                val rootPackage = activeRoot?.packageName?.toString()?.takeIf { it.isNotBlank() }
                val rootWindow = activeRoot?.windowId ?: -1
                if ((rootPackage != null && rootPackage != packageName) ||
                    (rootWindow >= 0 && windowId >= 0 && rootWindow != windowId)) return@launch
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
                screenshotIdentityGeneration.get() != identityGeneration) return@launch
            val snapshot = preferredAccessibilitySnapshot(activeSnapshot, sourceSnapshot)
            if (shouldCaptureEmptyTreeWeChatOpen(
                    eventType = eventType,
                    className = eventClass,
                    visibleText = eventText,
                    activeTreeUsable = hasReadableChatContent(activeSnapshot),
                    sourceTreeUsable = hasReadableChatContent(sourceSnapshot),
                )
            ) {
                emptyTreeWeChatCaptureDelays(eventText).forEach { delayMillis ->
                    mainHandler.postDelayed({
                        if (screenshotIdentityGeneration.get() == identityGeneration) captureEmptyTreeWeChatScreenshot()
                    }, delayMillis)
                }
                return@launch
            }
            if (regularCapture && snapshot != null) submitChatViewport(packageName, windowId, snapshot, generation)
        }.invokeOnCompletion {
            // 即使协程尚未启动就被取消，也必须归还我们持有的事件副本。
            @Suppress("DEPRECATION")
            capturedEvent.recycle()
        }
    }

    override fun onInterrupt() = resetScreenshotIdentity()

    override fun onServiceConnected() {
        super.onServiceConnected()
        val database = CaptureDatabase.create(applicationContext)
        val activeChatContextStore = ActiveChatContextStore(applicationContext)
        activeChatContextStore.clear()
        val activeMediaCapturer = WindowMediaCapturer(
            context = applicationContext,
            screenshotSource = WindowScreenshotter(this),
            captureAllowed = { CollectionConsent.enabled(applicationContext) },
            captureGeneration = captureRequestGeneration::get,
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
            deviceId = { DataCollector.deviceId(applicationContext) },
            wakeUploader = CaptureUploader::wake,
            captureAllowed = { CollectionConsent.enabled(applicationContext) },
            mediaCapturer = activeMediaCapturer,
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
        val parsed = AdapterRegistry.forPackage(packageName)?.parse(snapshot) as? ParseResult.Success
        val conversation = parsed?.viewport?.conversation
        val key = conversation?.stableKeyOrNull()
        if (key == null || conversation.identityConfidence < 0.8) {
            coordinator?.resetConversationIdentity()
            debouncer.close()
            return
        }
        debouncer.submit(
            windowId,
            viewportCaptureSignature(packageName, snapshot.stableTreeSignature(), generation),
            StableViewport(packageName, windowId, snapshot, confirmationAttempt),
            contextKey = key,
        )
    }

    private fun onStableViewport(viewport: StableViewport) {
        val identityGeneration = screenshotIdentityGeneration.get()
        // 限频等待期间可能已切换会话或退出 App；后台复核当前树，不阻塞输入法主线程。
        backgroundScope.launch {
            if (!CollectionConsent.enabled(this@PassiveChatAccessibilityService) ||
                screenshotIdentityGeneration.get() != identityGeneration) return@launch
            val root = rootInActiveWindow ?: return@launch
            val current = try {
                if (root.packageName?.toString() != viewport.packageName || root.windowId != viewport.windowId) {
                    return@launch
                }
                treeReader.read(root)
            } finally {
                recycleRoot(root)
            } ?: return@launch
            if (!samePendingChat(viewport.packageName, viewport.snapshot, current)) return@launch
            val activeCoordinator = coordinator ?: return@launch
            if (!CollectionConsent.enabled(this@PassiveChatAccessibilityService) ||
                screenshotIdentityGeneration.get() != identityGeneration) return@launch
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

    private fun captureCurrentForegroundViewport(expectedPackage: String, confirmationAttempt: Int = 0) {
        if (!CollectionConsent.enabled(this) || !isForegroundChatCapturePackage(expectedPackage)) return
        val generation = snapshotGeneration.incrementAndGet()
        val identityGeneration = screenshotIdentityGeneration.get()
        backgroundScope.launch {
            if (!CollectionConsent.enabled(this@PassiveChatAccessibilityService) ||
                snapshotGeneration.get() != generation ||
                screenshotIdentityGeneration.get() != identityGeneration) return@launch
            val root = rootInActiveWindow ?: return@launch
            try {
                val packageName = root.packageName?.toString()
                if (packageName != expectedPackage) return@launch
                val snapshot = treeReader.read(root) ?: return@launch
                val windowId = root.windowId
                if (!CollectionConsent.enabled(this@PassiveChatAccessibilityService) ||
                    screenshotIdentityGeneration.get() != identityGeneration) return@launch
                submitChatViewport(packageName, windowId, snapshot, generation, confirmationAttempt)
            } finally {
                recycleRoot(root)
            }
        }
    }

    private suspend fun isCurrentScreenshotWindow(windowId: Int, generation: Long): Boolean =
        withContext(Dispatchers.Main) {
            if (!CollectionConsent.enabled(this@PassiveChatAccessibilityService) ||
                screenshotIdentityGeneration.get() != generation ||
                (getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked == true) return@withContext false
            val root = rootInActiveWindow ?: return@withContext false
            try { root.packageName?.toString() == WECHAT_PACKAGE && root.windowId == windowId }
            finally { recycleRoot(root) }
        }

    private fun captureEmptyTreeWeChatScreenshot() {
        if (!CollectionConsent.enabled(this)) return
        val targetWindow = windows.firstOrNull { window ->
            window.type == AccessibilityWindowInfo.TYPE_APPLICATION && window.isActive
        } ?: return
        val root = targetWindow.root
        val packageName = root?.packageName?.toString()
        root?.let(::recycleRoot)
        if (packageName != WECHAT_PACKAGE) return
        val windowId = targetWindow.id

        val windowRect = Rect().also(targetWindow::getBoundsInScreen)
        val displayMetrics = resources.displayMetrics
        val windowBounds = if (windowRect.width() > 0 && windowRect.height() > 0) {
            IntRect(windowRect.left, windowRect.top, windowRect.right, windowRect.bottom)
        } else {
            IntRect(0, 0, displayMetrics.widthPixels, displayMetrics.heightPixels)
        }
        val inputMethodTop = windows.asSequence()
            .filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            .map { window -> Rect().also(window::getBoundsInScreen).top }
            .filter { it > windowBounds.top }
            .minOrNull()
        val screenshotBounds = emptyTreeScreenshotBounds(windowBounds, inputMethodTop)
        if (screenshotBounds.right <= screenshotBounds.left || screenshotBounds.bottom - screenshotBounds.top < 400) return

        val identityGeneration = screenshotIdentityGeneration.get()
        backgroundScope.launch {
            emptyTreeCaptureMutex.withLock {
                if (!isCurrentScreenshotWindow(windowId, identityGeneration)) return@withLock
                val resolver = screenshotIdentityResolver
                val resolverVersion = resolver?.version() ?: 0L
                val asset = mediaCapturer?.capture(
                    windowId = windowId,
                    windowBounds = windowBounds,
                    requests = listOf(MediaCaptureRequest(0, screenshotBounds, lossyWebp = true)),
                )?.get(0) ?: return@withLock
                val preferences = getSharedPreferences(FALLBACK_PREFERENCES, Context.MODE_PRIVATE)
                if (preferences.getString(LAST_EMPTY_TREE_SCREENSHOT_SHA, null) == asset.sha256 &&
                    preferences.getString("last_title_identity_status", null) == "confirmed") return@withLock
                val capturedAt = System.currentTimeMillis()
                val firstIdentity = resolver?.resolve(asset, resolverVersion) ?: unresolvedWechatScreenshotIdentity()
                if (!firstIdentity.isChatPage) return@withLock
                suspend fun persist(identity: ScreenshotConversationIdentity): CapturePersistResult {
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
                                "identity_unavailable" to (identity.status != "confirmed").toString(),
                                "conversation_identity_source" to identity.source,
                                "conversation_identity_status" to identity.status,
                                "conversation_identity_observed_title" to identity.observedTitle.orEmpty(),
                                "conversation_identity_previous_key" to identity.previousKey.orEmpty(),
                            ),
                        )),
                        pendingAssetsByMessage = mapOf(0 to asset),
                    ) ?: CapturePersistResult.FAILED
                    if (result != CapturePersistResult.FAILED) {
                        preferences.edit().putString(LAST_EMPTY_TREE_SCREENSHOT_SHA, asset.sha256)
                            .putString("last_title_identity_status", identity.status).apply()
                    }
                    return result
                }
                persistScreenshotBeforeConfirmation(firstIdentity, ::persist) {
                    delay(800)
                    if (!isCurrentScreenshotWindow(windowId, identityGeneration)) return@persistScreenshotBeforeConfirmation null
                    val nextAsset = mediaCapturer?.capture(
                        windowId = windowId,
                        windowBounds = windowBounds,
                        requests = listOf(MediaCaptureRequest(0, screenshotBounds, lossyWebp = true)),
                    )?.get(0) ?: return@persistScreenshotBeforeConfirmation null
                    if (!isCurrentScreenshotWindow(windowId, identityGeneration)) return@persistScreenshotBeforeConfirmation null
                    resolver?.resolve(nextAsset, resolverVersion)?.takeIf { it.isChatPage }
                }

            }
        }
    }

    private fun onStableFallbackRequest(request: NotificationScreenshotFallbackRequest) {
        backgroundScope.launch {
            fallbackCaptureMutex.withLock {
                if (!CollectionConsent.enabled(this@PassiveChatAccessibilityService)) return@withLock
                if (pendingFallbackRequest() != request) return@withLock
                val descriptor = pendingNotificationScreenshotDescriptor(request) ?: run {
                    completeFallback(request)
                    return@withLock
                }
                val target = currentFallbackTarget(request) ?: return@withLock
                val asset = mediaCapturer?.capture(
                    windowId = target.windowId,
                    windowBounds = target.windowBounds,
                    requests = listOf(
                        MediaCaptureRequest(
                            messageIndex = 0,
                            bounds = target.chatViewport?.bounds ?: notificationFallbackBounds(target.windowBounds),
                            inputAreaBounds = target.chatViewport?.inputAreaBounds,
                            lossyWebp = true,
                        ),
                    ),
                )?.get(0)
                if (asset == null) {
                    scheduleFallbackRetry(request)
                    return@withLock
                }
                val targetAfterCapture = currentFallbackTarget(request)
                if (targetAfterCapture?.windowId != target.windowId ||
                    targetAfterCapture?.chatViewport != target.chatViewport || pendingFallbackRequest() != request) {
                    return@withLock
                }
                val preferences = getSharedPreferences(FALLBACK_PREFERENCES, Context.MODE_PRIVATE)
                val screenshotShaKey = if (request.packageName == WECHAT_PACKAGE) {
                    LAST_SCREENSHOT_SHA
                } else {
                    "$LAST_SCREENSHOT_SHA:${request.packageName}"
                }
                if (preferences.getString(screenshotShaKey, null) == asset.sha256 &&
                    preferences.getString("$screenshotShaKey:identity", null) == descriptor.externalKey) {
                    completeFallback(request)
                    return@withLock
                }

                val persistResult = coordinator?.captureParsed(
                    conversation = CapturedConversation(
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
                                "notification_key" to request.notificationKey,
                                "source_package" to request.packageName,
                                "identity_unavailable" to "true",
                            ),
                        ),
                    ),
                    pendingAssetsByMessage = mapOf(0 to asset),
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
    ): FallbackTarget? = suspendCancellableCoroutine { continuation ->
        mainHandler.post {
            if (!continuation.isActive) return@post
            val root = rootInActiveWindow
            val foregroundPackage = root?.packageName?.toString()
            val inputMethodVisible = windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            val screenLocked = (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceLocked
            if (!shouldCaptureNotificationFallback(
                    screenLocked = screenLocked,
                    foregroundPackage = foregroundPackage,
                    inputMethodVisible = inputMethodVisible,
                    targetPackage = request.packageName,
                ) || root == null
            ) {
                root?.let(::recycleRoot)
                continuation.resume(null)
                return@post
            }
            val snapshot = treeReader.read(root)
            val chatViewport = snapshot?.let { notificationChatViewport(request.packageName, it) }
            // QQ/抖音有页面树时必须确认聊天页，不能把消息列表/短视频页面当作聊天截图。
            // 微信空树继续保留既有补偿路径；可识别页面同样使用真实聊天区边界。
            if (chatViewport == null && request.packageName != WECHAT_PACKAGE) {
                recycleRoot(root)
                continuation.resume(null)
                return@post
            }
            val bounds = Rect()
            root.getBoundsInScreen(bounds)
            val target = FallbackTarget(
                windowId = root.windowId,
                windowBounds = IntRect(bounds.left, bounds.top, bounds.right, bounds.bottom),
                chatViewport = chatViewport,
            )
            recycleRoot(root)
            continuation.resume(target.takeIf { bounds.width() > 0 && bounds.height() > 0 })
        }
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

internal fun emptyTreeScreenshotBounds(windowBounds: IntRect, inputMethodTop: Int?): IntRect = IntRect(
    left = windowBounds.left,
    top = windowBounds.top + 80,
    right = windowBounds.right,
    // 空树设备无法定位微信输入栏。没有系统键盘时保留完整窗口，避免截断最后一条消息。
    bottom = inputMethodTop?.coerceAtMost(windowBounds.bottom) ?: windowBounds.bottom,
)

internal fun viewportCaptureSignature(packageName: String, treeSignature: String, eventGeneration: Long): String =
    if (isForegroundChatCapturePackage(packageName))
        "$treeSignature:$eventGeneration" else treeSignature

internal fun samePendingChat(packageName: String, previous: UiNodeSnapshot, current: UiNodeSnapshot): Boolean {
    val adapter = AdapterRegistry.forPackage(packageName) ?: return false
    val before = (adapter.parse(previous) as? ParseResult.Success)?.viewport?.conversation ?: return false
    val after = (adapter.parse(current) as? ParseResult.Success)?.viewport?.conversation ?: return false
    val key = before.stableKeyOrNull() ?: return false
    return before.identityConfidence >= 0.8 && after.identityConfidence >= 0.8 && key == after.stableKeyOrNull()
}
