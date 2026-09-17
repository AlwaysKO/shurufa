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
import com.yuyan.imemodule.data.capture.adapter.AdapterRegistry
import com.yuyan.imemodule.data.capture.db.CaptureDatabase
import com.yuyan.imemodule.data.capture.media.WindowMediaCapturer
import com.yuyan.imemodule.data.capture.media.WindowScreenshotter
import com.yuyan.imemodule.data.capture.media.MediaCaptureRequest
import com.yuyan.imemodule.data.capture.media.MlKitWechatScreenshotIdentityResolver
import com.yuyan.imemodule.data.capture.media.ScreenshotConversationIdentityResolver
import com.yuyan.imemodule.data.capture.media.screenshotConversationIdentity
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
    private var captureDatabase: CaptureDatabase? = null
    private var coordinator: CaptureCoordinator? = null
    private var mediaCapturer: WindowMediaCapturer? = null
    private var screenshotIdentityResolver: ScreenshotConversationIdentityResolver? = null
    private var fallbackConnection: CancellableTask? = null
    private var foregroundCaptureConnection: CancellableTask? = null
    private var fallbackStore: NotificationScreenshotFallbackStore? = null
    private val debouncer = ViewportDebouncer(
        scheduler = CoroutineDebounceScheduler(backgroundScope),
        onStable = ::onStableViewport,
    )
    private val fallbackDebouncer = ViewportDebouncer(
        scheduler = CoroutineDebounceScheduler(backgroundScope),
        stableDelayMillis = FALLBACK_DELAY_MILLIS,
        onStable = ::onStableFallbackRequest,
    )

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!CollectionConsent.enabled(this)) return
        val packageName = event.packageName?.toString() ?: return
        if (packageName !in SUPPORTED_PACKAGES) return
        val eventText = listOfNotNull(
            event.text?.joinToString(" "),
            event.contentDescription?.toString(),
        ).joinToString(" ")
        val regularCapture = shouldCaptureForegroundChatEvent(event.eventType, event.className?.toString(), eventText)
        val possibleEmptyTreeCapture = shouldCaptureEmptyTreeWeChatOpen(
            eventType = event.eventType,
            className = event.className?.toString(),
            visibleText = eventText,
            activeTreeUsable = false,
            sourceTreeUsable = false,
        )
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
        val activeRoot = rootInActiveWindow
        val eventSource = event.source
        val activeSnapshot = try {
            treeReader.read(activeRoot)
        } finally {
            activeRoot?.let(::recycleRoot)
        }
        val sourceSnapshot = try {
            treeReader.read(eventSource)
        } finally {
            if (eventSource !== activeRoot) eventSource?.let(::recycleRoot)
        }
        val snapshot = preferredAccessibilitySnapshot(activeSnapshot, sourceSnapshot) ?: return
        if (shouldCaptureEmptyTreeWeChatOpen(
                eventType = event.eventType,
                className = event.className?.toString(),
                visibleText = eventText,
                activeTreeUsable = activeSnapshot.isUsableAccessibilitySnapshot(),
                sourceTreeUsable = sourceSnapshot.isUsableAccessibilitySnapshot(),
            )
        ) {
            emptyTreeWeChatCaptureDelays(eventText).forEach { delayMillis ->
                mainHandler.postDelayed(::captureEmptyTreeWeChatScreenshot, delayMillis)
            }
            return
        }
        if (!regularCapture) return
        val generation = snapshotGeneration.incrementAndGet()
        backgroundScope.launch {
            if (snapshotGeneration.get() != generation) return@launch
            val viewport = StableViewport(packageName, windowId, snapshot)
            debouncer.submit(
                windowId,
                viewportCaptureSignature(packageName, snapshot.stableTreeSignature(), generation),
                viewport,
            )
        }
    }

    override fun onInterrupt() = Unit

    override fun onServiceConnected() {
        super.onServiceConnected()
        val database = CaptureDatabase.create(applicationContext)
        val activeChatContextStore = ActiveChatContextStore(applicationContext)
        activeChatContextStore.clear()
        val activeMediaCapturer = WindowMediaCapturer(
            context = applicationContext,
            screenshotSource = WindowScreenshotter(this),
        )
        captureDatabase = database
        mediaCapturer = activeMediaCapturer
        screenshotIdentityResolver = MlKitWechatScreenshotIdentityResolver()
        fallbackStore = NotificationScreenshotFallbackStore(applicationContext)
        coordinator = CaptureCoordinator(
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

    private fun onStableViewport(viewport: StableViewport) {
        val activeCoordinator = coordinator ?: return
        backgroundScope.launch {
            if (!CollectionConsent.enabled(this@PassiveChatAccessibilityService)) return@launch
            activeCoordinator.capture(viewport.packageName, viewport.snapshot, viewport.windowId)
        }
    }

    private fun captureCurrentForegroundViewport(expectedPackage: String) {
        if (!CollectionConsent.enabled(this) || !isForegroundChatCapturePackage(expectedPackage)) return
        val root = rootInActiveWindow ?: return
        try {
            val packageName = root.packageName?.toString()
            if (packageName != expectedPackage) return
            val snapshot = treeReader.read(root) ?: return
            val windowId = root.windowId
            val generation = snapshotGeneration.incrementAndGet()
            backgroundScope.launch {
                if (snapshotGeneration.get() != generation) return@launch
                val viewport = StableViewport(packageName, windowId, snapshot)
                debouncer.submit(
                    windowId,
                    viewportCaptureSignature(packageName, snapshot.stableTreeSignature(), generation),
                    viewport,
                )
            }
        } finally {
            recycleRoot(root)
        }
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

        backgroundScope.launch {
            val asset = mediaCapturer?.capture(
                windowId = windowId,
                windowBounds = windowBounds,
                requests = listOf(MediaCaptureRequest(0, screenshotBounds, lossyWebp = true)),
            )?.get(0) ?: return@launch
            val preferences = getSharedPreferences(FALLBACK_PREFERENCES, Context.MODE_PRIVATE)
            if (preferences.getString(LAST_EMPTY_TREE_SCREENSHOT_SHA, null) == asset.sha256) return@launch
            val identity = screenshotIdentityResolver?.resolve(asset)
                ?: screenshotConversationIdentity(null, asset.perceptualHash.orEmpty())
            val capturedAt = System.currentTimeMillis()
            val result = coordinator?.captureParsed(
                conversation = CapturedConversation(
                    platform = ChatPlatform.WECHAT,
                    accountKey = "wechat-empty-tree",
                    externalKey = identity.externalKey,
                    displayName = identity.displayName,
                    conversationType = identity.conversationType,
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
                        "identity_unavailable" to (identity.source != "on_device_title_ocr").toString(),
                        "conversation_identity_source" to identity.source,
                    ),
                )),
                pendingAssetsByMessage = mapOf(0 to asset),
            ) ?: CapturePersistResult.FAILED
            if (result != CapturePersistResult.FAILED) {
                preferences.edit().putString(LAST_EMPTY_TREE_SCREENSHOT_SHA, asset.sha256).apply()
            }
        }
    }

    private fun onStableFallbackRequest(request: NotificationScreenshotFallbackRequest) {
        backgroundScope.launch {
            fallbackCaptureMutex.withLock {
                if (!CollectionConsent.enabled(this@PassiveChatAccessibilityService)) return@withLock
                if (pendingFallbackRequest() != request) return@withLock
                val descriptor = notificationScreenshotFallbackDescriptor(request.packageName) ?: run {
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
                            bounds = notificationFallbackBounds(target.windowBounds),
                            lossyWebp = true,
                        ),
                    ),
                )?.get(0)
                if (asset == null) {
                    scheduleFallbackRetry(request)
                    return@withLock
                }
                val targetAfterCapture = currentFallbackTarget(request)
                if (targetAfterCapture?.windowId != target.windowId || pendingFallbackRequest() != request) {
                    return@withLock
                }
                val preferences = getSharedPreferences(FALLBACK_PREFERENCES, Context.MODE_PRIVATE)
                val screenshotShaKey = if (request.packageName == WECHAT_PACKAGE) {
                    LAST_SCREENSHOT_SHA
                } else {
                    "$LAST_SCREENSHOT_SHA:${request.packageName}"
                }
                if (preferences.getString(screenshotShaKey, null) == asset.sha256) {
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
                                "notification_key" to request.notificationKey,
                                "source_package" to request.packageName,
                                "identity_unavailable" to "true",
                            ),
                        ),
                    ),
                    pendingAssetsByMessage = mapOf(0 to asset),
                ) ?: CapturePersistResult.FAILED
                if (persistResult != CapturePersistResult.FAILED) {
                    preferences.edit().putString(screenshotShaKey, asset.sha256).apply()
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
            val bounds = Rect()
            root.getBoundsInScreen(bounds)
            val target = FallbackTarget(
                windowId = root.windowId,
                windowBounds = IntRect(bounds.left, bounds.top, bounds.right, bounds.bottom),
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
    )

    private data class FallbackTarget(
        val windowId: Int,
        val windowBounds: IntRect,
    )

    private companion object {
        val SUPPORTED_PACKAGES = ACCESSIBILITY_CHAT_EVENT_PACKAGES
        const val FOREGROUND_SEND_RENDER_DELAY_MILLIS = 700L
        const val FALLBACK_DELAY_MILLIS = 1_200L
        const val FALLBACK_RETRY_MILLIS = 5_000L
        const val FALLBACK_DEBOUNCE_WINDOW_ID = -1
        const val FALLBACK_IDENTITY_CONFIDENCE = 0.8
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
    if (packageName == "com.tencent.mm") "$treeSignature:$eventGeneration" else treeSignature
