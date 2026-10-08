package com.yuyan.imemodule.service.capture

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.yuyan.imemodule.data.collect.CollectionConsent
import com.yuyan.imemodule.data.collect.ImageUploadRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAccessibilityService
import org.robolectric.shadows.ShadowAccessibilityNodeInfo
import com.yuyan.imemodule.data.capture.ui.UiNodeSnapshot
import kotlinx.serialization.json.Json
import org.robolectric.Shadows
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [ChatCaptureThreadingTest.ServiceShadow::class, ChatCaptureThreadingTest.NodeShadow::class])
class ChatCaptureThreadingTest {
    // Robolectric回拨时钟，但输入/游戏单例仍可能继承上一类状态。
    @org.junit.Before @org.junit.After fun resetBackgroundWorkForTest() {
        com.yuyan.imemodule.data.collect.resetImageInputForTest()
        com.yuyan.imemodule.data.collect.resetGameWorkRuntimeForTest()
    }

    @Implements(AccessibilityService::class)
    class ServiceShadow : ShadowAccessibilityService() {
        @Implementation
        fun getRootInActiveWindow(): AccessibilityNodeInfo? = root?.let { AccessibilityNodeInfo.obtain(it) }
        companion object { @JvmField var root: AccessibilityNodeInfo? = null }
    }

    @Implements(AccessibilityNodeInfo::class)
    class NodeShadow : ShadowAccessibilityNodeInfo() {
        @Implementation
        override fun getChildCount(): Int {
            readOnMain = Looper.myLooper() == Looper.getMainLooper()
            read.countDown()
            onRead?.invoke()
            return super.getChildCount()
        }
        companion object {
            @JvmField var read = CountDownLatch(1)
            @Volatile @JvmField var readOnMain = false
            @Volatile @JvmField var onRead: (() -> Unit)? = null
        }
    }

    @Test fun promptEntryAndSendReadCurrentChatOffMainDuringInputCooldownWithoutNetwork() {
        val settings = com.yuyan.imemodule.data.capture.adapter.ChatCaptureSettings
        val runtimeField = settings.javaClass.getDeclaredField("runtime").apply { isAccessible = true }
        val previousRuntime = runtimeField.get(settings)
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val runtime = com.yuyan.imemodule.data.capture.adapter.ChatCaptureRefreshController(
            allowed = { true },
            loadVersion = { com.yuyan.imemodule.data.capture.adapter.CaptureAppVersion(123, "123") },
            readCache = { null }, writeCache = { _, _ -> }, fetch = { requests.incrementAndGet(); null },
        )
        try {
            runtimeField.set(settings, runtime)
            for (pkg in FOREGROUND_CHAT_CAPTURE_PACKAGES) for (afterSend in listOf(false, true)) {
                withPromptService(pkg) { service ->
                    ImageUploadRuntime.noteKeyActivity()
                    assertFalse("保持原三秒重活避让", ImageUploadRuntime.isInputIdle())
                    requestPrompt(service, pkg, afterSend)
                    assertTrue("首进入/发送轻量读树不应等待三秒：$pkg, send=$afterSend",
                        NodeShadow.read.await(2, TimeUnit.SECONDS))
                    assertFalse("即时读树也必须在后台", NodeShadow.readOnMain)
                    assertFalse("轻量许可不能解除截图编码等重活避让", ImageUploadRuntime.isInputIdle())
                }
            }
            assertEquals("即时取帧前的版本适配不能联网", 0, requests.get())
        } finally { runtimeField.set(settings, previousRuntime) }
    }

    @Test fun promptSendWaitsForTouchReleaseBeforeReadingChatTree() {
        withPromptService("com.ss.android.ugc.aweme") { service ->
            val touch = Any()
            ImageUploadRuntime.noteTouch(0, touch)
            requestPrompt(service, "com.ss.android.ugc.aweme", true)
            assertFalse("手指尚按住时不得开始读树", NodeShadow.read.await(150, TimeUnit.MILLISECONDS))
            ImageUploadRuntime.noteTouch(1, touch)
            assertTrue("同一发送请求在释放后应及时读树", NodeShadow.read.await(2, TimeUnit.SECONDS))
            assertFalse(NodeShadow.readOnMain)
            assertFalse("释放不代表三秒重活冷却结束", ImageUploadRuntime.isInputIdle())
        }
    }

    @Test fun queuedPromptCannotReadNewPageAfterNavigationGenerationChanges() {
        withPromptService("com.tencent.mobileqq") { service ->
            val scope = service.javaClass.getDeclaredField("backgroundScope").apply { isAccessible = true }
                .get(service) as CoroutineScope
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val finished = CountDownLatch(1)
            scope.launch { started.countDown(); release.await(3, TimeUnit.SECONDS) }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            try {
                requestPrompt(service, "com.tencent.mobileqq", true)
                (service.javaClass.getDeclaredField("screenshotIdentityGeneration").apply { isAccessible = true }
                    .get(service) as AtomicLong).incrementAndGet()
                release.countDown()
                scope.launch { finished.countDown() }
                assertTrue(finished.await(2, TimeUnit.SECONDS))
                assertFalse("导航后旧请求不得读取新页", NodeShadow.read.await(150, TimeUnit.MILLISECONDS))
            } finally { release.countDown() }
        }
    }

    @Test fun sendReplacesSuspendedEntryFrameInSameConversation() {
        withSuspendedPromptFrame { service, firstJob, _, secondFrame ->
            ImageUploadRuntime.noteKeyActivity()
            requestPrompt(service, "com.tencent.mobileqq", true)
            assertTrue("已暂停的开页取帧不得挡住发送后的最新帧", secondFrame.await(2, TimeUnit.SECONDS))
            assertTrue("同一会话的旧开页任务应被最新发送替换", firstJob.isCancelled)
        }
    }

    @Test fun latestSendReplacesSuspendedEarlierSendFrameInSameConversation() {
        withSuspendedPromptFrame(afterSend = true) { service, firstJob, _, secondFrame ->
            ImageUploadRuntime.noteKeyActivity()
            requestPrompt(service, "com.tencent.mobileqq", true)
            assertTrue("同页再次发送应保留最终帧，不能只留下前一条消息", secondFrame.await(2, TimeUnit.SECONDS))
            assertTrue("最新发送须先取消同页旧帧处理，保持单帧上限", firstJob.isCancelled)
        }
    }

    @Test fun newConversationSendDoesNotCancelPreviouslyAcceptedFrame() {
        withSuspendedPromptFrame { service, firstJob, release, _ ->
            (service.javaClass.getDeclaredField("screenshotIdentityGeneration").apply { isAccessible = true }
                .get(service) as AtomicLong).incrementAndGet()
            ImageUploadRuntime.noteKeyActivity()
            requestPrompt(service, "com.tencent.mobileqq", true)
            Thread.sleep(150)
            assertTrue("跨会话不得取消已经接收且仍待处理的旧帧", firstJob.isActive)
            assertFalse(firstJob.isCancelled)
            release.complete(Unit)
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(4))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (firstJob.isActive && System.nanoTime() < deadline) Thread.sleep(10)
            assertFalse(firstJob.isActive)
            assertFalse("旧帧应正常完成，不应伪装成取消后完成", firstJob.isCancelled)
        }
    }

    @Test fun sendKeepsFastPermitAcrossShortAutomaticScroll() {
        for (scrollBeforeRequest in listOf(false, true)) withPromptService("com.ss.android.ugc.aweme") { service ->
            val scope = service.javaClass.getDeclaredField("backgroundScope").apply { isAccessible = true }
                .get(service) as CoroutineScope
            val gate = ScrollCaptureGate(com.yuyan.imemodule.data.capture.ui.CoroutineDebounceScheduler(scope)) {}
            service.javaClass.getDeclaredField("scrollGate").apply { isAccessible = true }.set(service, gate)
            val generation = (service.javaClass.getDeclaredField("screenshotIdentityGeneration").apply { isAccessible = true }
                .get(service) as AtomicLong).get()
            val screenshotScope = ScreenshotScope(requireNotNull(ServiceShadow.root).windowId, generation)
            ImageUploadRuntime.noteKeyActivity()
            if (scrollBeforeRequest) gate.scrolled(screenshotScope)
            requestPrompt(service, "com.ss.android.ugc.aweme", true)
            if (!scrollBeforeRequest) gate.scrolled(screenshotScope)
            assertFalse("自动滚动尚未停止时不应读取中间画面", NodeShadow.read.await(150, TimeUnit.MILLISECONDS))
            assertTrue("300ms停止后应沿用发送许可，不得退回3秒输入冷却", NodeShadow.read.await(1, TimeUnit.SECONDS))
            assertFalse(NodeShadow.readOnMain)
            assertFalse(ImageUploadRuntime.isInputIdle())
        }
    }

    @Test fun explicitSendEventSurvivesExistingScrollGate() {
        withPromptService("com.ss.android.ugc.aweme") { service ->
            val scope = service.javaClass.getDeclaredField("backgroundScope").apply { isAccessible = true }
                .get(service) as CoroutineScope
            val gate = ScrollCaptureGate(com.yuyan.imemodule.data.capture.ui.CoroutineDebounceScheduler(scope)) {}
            service.javaClass.getDeclaredField("scrollGate").apply { isAccessible = true }.set(service, gate)
            val generation = (service.javaClass.getDeclaredField("screenshotIdentityGeneration").apply { isAccessible = true }
                .get(service) as AtomicLong).get()
            ImageUploadRuntime.noteKeyActivity()
            val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED).apply {
                packageName = "com.ss.android.ugc.aweme"
                className = "android.widget.Button"
                text.add("发送")
            }
            gate.scrolled(ScreenshotScope(event.windowId, generation))
            try {
                service.onAccessibilityEvent(event)
                assertFalse("发送不能在滚动中读中间画面", NodeShadow.read.await(150, TimeUnit.MILLISECONDS))
                assertTrue("真实发送事件不能被scroll早退吞掉", NodeShadow.read.await(1, TimeUnit.SECONDS))
                assertFalse(ImageUploadRuntime.isInputIdle())
            } finally { event.recycle() }
        }
    }

    private fun withSuspendedPromptFrame(afterSend: Boolean = false, work: (PassiveChatAccessibilityService, Job, CompletableDeferred<Unit>, CountDownLatch) -> Unit) {
        withPromptService("com.tencent.mobileqq") { service ->
            val fixture = Json.decodeFromString<UiNodeSnapshot>(
                javaClass.getResourceAsStream("/capture/qq-chat-9.3.60.json")!!.bufferedReader().use { it.readText() })
            fun node(tree: UiNodeSnapshot): AccessibilityNodeInfo = AccessibilityNodeInfo.obtain().apply {
                packageName = "com.tencent.mobileqq"; className = tree.className; viewIdResourceName = tree.viewId
                text = tree.text; contentDescription = tree.contentDescription
                setBoundsInScreen(Rect(tree.bounds.left, tree.bounds.top, tree.bounds.right, tree.bounds.bottom))
                tree.children.forEach { Shadows.shadowOf(this).addChild(node(it)) }
            }
            ServiceShadow.root = node(fixture)
            val accepted = CountDownLatch(1)
            val secondFrame = CountDownLatch(1)
            val release = CompletableDeferred<Unit>()
            val frames = java.util.concurrent.atomic.AtomicInteger()
            val coordinator = com.yuyan.imemodule.data.capture.CaptureCoordinator(
                store = object : com.yuyan.imemodule.data.capture.CaptureOutboxStore {
                    override suspend fun enqueueIfNew(seenMessage: com.yuyan.imemodule.data.capture.db.SeenMessageEntity,
                        pendingMessage: com.yuyan.imemodule.data.capture.db.PendingMessageEntity,
                        pendingAssets: List<com.yuyan.imemodule.data.capture.db.PendingAssetEntity>): Boolean = true
                }, deviceId = { "test" }, wakeUploader = {},
                mediaCapturer = com.yuyan.imemodule.data.capture.media.MediaAssetCapturer { _, _, _ ->
                    val attempt = requireNotNull(currentCoroutineContext()[com.yuyan.imemodule.data.capture.media.ChatCaptureAttempt])
                    check(attempt.acceptFrame())
                    if (frames.incrementAndGet() == 1) { accepted.countDown(); release.await() }
                    else secondFrame.countDown()
                    emptyMap()
                },
            )
            service.javaClass.getDeclaredField("coordinator").apply { isAccessible = true }.set(service, coordinator)
            requestPrompt(service, "com.tencent.mobileqq", afterSend)
            try {
                assertTrue("开页任务应已接收帧并暂停于后台处理", accepted.await(2, TimeUnit.SECONDS))
                val firstJob = service.javaClass.getDeclaredField("promptCaptureJob").apply { isAccessible = true }.get(service) as Job
                work(service, firstJob, release, secondFrame)
            } finally { release.complete(Unit) }
        }
    }

    private fun requestPrompt(service: PassiveChatAccessibilityService, packageName: String, afterSend: Boolean) {
        service.javaClass.getDeclaredMethod("requestPromptChatCapture", String::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(service, packageName, afterSend)
    }

    private fun withPromptService(packageName: String, work: (PassiveChatAccessibilityService) -> Unit) {
        com.yuyan.imemodule.data.collect.resetImageInputForTest()
        NodeShadow.read = CountDownLatch(1)
        NodeShadow.readOnMain = false
        NodeShadow.onRead = null
        val service = Robolectric.buildService(PassiveChatAccessibilityService::class.java).create().get()
        CollectionConsent.setEnabled(service, true)
        ServiceShadow.root = AccessibilityNodeInfo.obtain().apply {
            this.packageName = packageName
            className = "android.widget.FrameLayout"
            setBoundsInScreen(Rect(0, 0, 1080, 2400))
        }
        try { work(service) }
        finally {
            CollectionConsent.setEnabled(service, false)
            service.onDestroy()
            ServiceShadow.root = null
            NodeShadow.onRead = null
            com.yuyan.imemodule.data.collect.resetImageInputForTest()
        }
    }

    @Test
    fun threeAppsShareScrollGateAndNavigationClearsIt() {
        for (pkg in FOREGROUND_CHAT_CAPTURE_PACKAGES) {
            val service = Robolectric.buildService(PassiveChatAccessibilityService::class.java).create().get()
            CollectionConsent.setEnabled(service, true)
            val gate = ScrollCaptureGate(com.yuyan.imemodule.data.capture.ui.DebounceScheduler { _, _ ->
                com.yuyan.imemodule.data.capture.ui.CancellableTask {}
            }) {}
            val field = PassiveChatAccessibilityService::class.java.getDeclaredField("scrollGate").apply { isAccessible = true }
            field.set(service, gate)
            val generation = PassiveChatAccessibilityService::class.java.getDeclaredField("captureRequestGeneration").apply { isAccessible = true }
            val token = generation.get(service) as AtomicLong
            val before = token.get()
            val scroll = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_SCROLLED).apply { packageName = pkg }
            val content = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply { packageName = pkg }
            try {
                service.onAccessibilityEvent(scroll)
                assertTrue("滚动须使在途截图失效：$pkg", token.get() > before)
                assertTrue(gate.isScrolling())
                service.onAccessibilityEvent(content)
                assertTrue("普通内容事件不能提前解除滚动：$pkg", gate.isScrolling())
                service.onInterrupt()
                assertFalse(gate.isScrolling())
            } finally {
                CollectionConsent.setEnabled(service, false)
                service.onDestroy()
                scroll.recycle(); content.recycle()
            }
        }
    }

    @Test
    fun `根包名未知时不能提前截断荣耀空树回退`() {
        NodeShadow.read = CountDownLatch(1)
        val service = Robolectric.buildService(PassiveChatAccessibilityService::class.java).create().get()
        CollectionConsent.setEnabled(service, true)
        ServiceShadow.root = AccessibilityNodeInfo.obtain()
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply {
            packageName = "com.tencent.mm"
        }
        try {
            service.onAccessibilityEvent(event)
            assertTrue("未知根仍应读取并进入可用性/回退判断", NodeShadow.read.await(3, TimeUnit.SECONDS))
        } finally {
            CollectionConsent.setEnabled(service, false)
            service.onDestroy()
            event.recycle()
            ServiceShadow.root = null
        }
    }

    @Test
    fun `读取期间的新内容事件不得丢弃已识别聊天的首次采集`() {
        val service = Robolectric.buildService(PassiveChatAccessibilityService::class.java).create().get()
        CollectionConsent.setEnabled(service, true)
        val snapshot = Json.decodeFromString<UiNodeSnapshot>(
            javaClass.getResourceAsStream("/capture/qq-chat-9.3.60.json")!!.bufferedReader().use { it.readText() },
        )
        fun node(tree: UiNodeSnapshot): AccessibilityNodeInfo = AccessibilityNodeInfo.obtain().apply {
            packageName = "com.tencent.mobileqq"
            className = tree.className
            viewIdResourceName = tree.viewId
            text = tree.text
            contentDescription = tree.contentDescription
            setBoundsInScreen(Rect(tree.bounds.left, tree.bounds.top, tree.bounds.right, tree.bounds.bottom))
            tree.children.forEach { Shadows.shadowOf(this).addChild(node(it)) }
        }
        ServiceShadow.root = node(snapshot)
        val generation = service.javaClass.getDeclaredField("snapshotGeneration").apply { isAccessible = true }
            .get(service) as AtomicLong
        // 模拟读树尚未结束，新内容事件已经到达并更新待处理代次；窗口和身份没有变化。
        NodeShadow.onRead = { generation.incrementAndGet(); Unit }
        val debouncer = requireNotNull(service.javaClass.getDeclaredField("debouncer").apply { isAccessible = true }.get(service))
        val activeContext = debouncer.javaClass.getDeclaredField("activeContext").apply { isAccessible = true }
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply {
            packageName = "com.tencent.mobileqq"
        }
        try {
            service.onAccessibilityEvent(event)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (synchronized(debouncer) { activeContext.get(debouncer) == null } && System.nanoTime() < deadline) {
                Thread.sleep(10)
            }
            assertNotNull("首个可识别视口必须到达首采调度器，不能被普通新事件饿死",
                synchronized(debouncer) { activeContext.get(debouncer) })
        } finally {
            NodeShadow.onRead = null
            CollectionConsent.setEnabled(service, false)
            service.onDestroy()
            event.recycle()
            ServiceShadow.root = null
        }
    }

    @Test
    fun `三个App的事件树遍历不得阻塞输入法主线程`() {
        for (pkg in FOREGROUND_CHAT_CAPTURE_PACKAGES) {
            NodeShadow.read = CountDownLatch(1)
            val service = Robolectric.buildService(PassiveChatAccessibilityService::class.java).create().get()
            CollectionConsent.setEnabled(service, true)
            ServiceShadow.root = AccessibilityNodeInfo.obtain().apply {
                packageName = pkg
                className = "android.view.ViewGroup"
                setBoundsInScreen(Rect(0, 0, 1080, 2400))
            }
            val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply {
                packageName = pkg
                className = "android.view.ViewGroup"
            }
            try {
                service.onAccessibilityEvent(event)
                assertTrue("应立即安排首次读取：$pkg", NodeShadow.read.await(3, TimeUnit.SECONDS))
                assertFalse("页面树跨进程读取不能占用键盘主线程：$pkg", NodeShadow.readOnMain)
            } finally {
                CollectionConsent.setEnabled(service, false)
                service.onDestroy()
                event.recycle()
                ServiceShadow.root = null
            }
        }
    }
    @Test
    fun `前台事件与延迟探测在读树前后台更新宿主版本且不联网`() {
        val settings = com.yuyan.imemodule.data.capture.adapter.ChatCaptureSettings
        val runtimeField = settings.javaClass.getDeclaredField("runtime").apply { isAccessible = true }
        val previousRuntime = runtimeField.get(settings)
        try {
            for (probe in listOf(false, true)) {
                NodeShadow.read = CountDownLatch(1)
                val loads = java.util.concurrent.atomic.AtomicInteger()
                val requests = java.util.concurrent.atomic.AtomicInteger()
                val versionReadOnMain = java.util.concurrent.atomic.AtomicBoolean()
                val runtime = com.yuyan.imemodule.data.capture.adapter.ChatCaptureRefreshController(
                    allowed = { true },
                    loadVersion = {
                        if (Looper.myLooper() == Looper.getMainLooper()) versionReadOnMain.set(true)
                        loads.incrementAndGet()
                        com.yuyan.imemodule.data.capture.adapter.CaptureAppVersion(123, "123")
                    }, readCache = { null }, writeCache = { _, _ -> }, fetch = { requests.incrementAndGet(); null },
                )
                val service = Robolectric.buildService(PassiveChatAccessibilityService::class.java).create().get()
                CollectionConsent.setEnabled(service, true)
                runtimeField.set(settings, runtime)
                ServiceShadow.root = AccessibilityNodeInfo.obtain().apply {
                    packageName = "com.ss.android.ugc.aweme"
                    className = "android.view.ViewGroup"
                    setBoundsInScreen(Rect(0, 0, 1080, 2400))
                }
                val observedVersion = java.util.concurrent.atomic.AtomicReference<Long?>()
                NodeShadow.onRead = { observedVersion.set(runtime.version("com.ss.android.ugc.aweme")?.code) }
                val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply {
                    packageName = "com.ss.android.ugc.aweme"
                }
                try {
                    if (probe) {
                        service.javaClass.getDeclaredMethod("captureCurrentForegroundViewport", String::class.java,
                            Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, kotlin.jvm.functions.Function0::class.java).apply { isAccessible = true }
                            .invoke(service, "com.ss.android.ugc.aweme", 0, false, { true })
                    } else service.onAccessibilityEvent(event)
                    assertTrue("应读到前台树", NodeShadow.read.await(3, TimeUnit.SECONDS))
                    // getChildCount 在回调前先释放 latch，等待同一读操作完成后再读取观测值。
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
                    while (observedVersion.get() == null && System.nanoTime() < deadline) Thread.sleep(5)
                    assertEquals("读树时须已选择当前宿主版本", 123L, observedVersion.get())
                    assertEquals(2, loads.get())
                    assertFalse(versionReadOnMain.get())
                    assertEquals(0, requests.get())
                } finally {
                    NodeShadow.onRead = null
                    CollectionConsent.setEnabled(service, false)
                    service.onDestroy()
                    event.recycle()
                    ServiceShadow.root = null
                }
            }
        } finally { runtimeField.set(settings, previousRuntime) }
    }

}
