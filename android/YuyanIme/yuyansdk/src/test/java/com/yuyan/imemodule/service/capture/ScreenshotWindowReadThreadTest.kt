package com.yuyan.imemodule.service.capture

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.yuyan.imemodule.data.capture.media.WindowMediaCapturer
import com.yuyan.imemodule.data.capture.media.ScreenshotSource
import com.yuyan.imemodule.data.capture.media.WindowScreenshotResult
import com.yuyan.imemodule.data.collect.CollectionConsent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAccessibilityService
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "mdpi", shadows = [ScreenshotWindowReadThreadTest.ServiceShadow::class])
class ScreenshotWindowReadThreadTest {
    private val diagnosticPlatforms = mutableListOf<String>()
    @Implements(AccessibilityService::class)
    class ServiceShadow : ShadowAccessibilityService() {
        @Implementation
        fun getRootInActiveWindow(): AccessibilityNodeInfo? {
            reads += "root" to Thread.currentThread()
            return root?.let { AccessibilityNodeInfo.obtain(it) }
        }

        @Implementation
        override fun getWindows(): List<AccessibilityWindowInfo> {
            reads += "windows" to Thread.currentThread()
            afterWindowRead?.invoke()
            return super.getWindows()
        }

        companion object {
            val reads = CopyOnWriteArrayList<Pair<String, Thread>>()
            var root: AccessibilityNodeInfo? = null
            var afterWindowRead: (() -> Unit)? = null
        }
    }

    @Test fun screenshotWindowAndNavigationChecksDoNotBlockKeyboardMainThread() = runBlocking {
        withService { service, drain, captures ->
            request(service)
            drain()
            assertEquals("首图仍应立即请求; idle=${com.yuyan.imemodule.data.collect.ImageUploadRuntime.isInputIdle()}; " +
                org.robolectric.shadows.ShadowLog.getLogsForTag("ChatCaptureTrace").joinToString { it.msg }, 1, captures())
            assertTrue(ServiceShadow.reads.any { it.first == "windows" })
            assertTrue(ServiceShadow.reads.any { it.first == "root" })
            for ((operation, thread) in ServiceShadow.reads) {
                assertFalse("截图 $operation 的 Binder 读取不能占用键盘主线程", thread === Looper.getMainLooper().thread)
            }
        }
    }

    @Test fun navigationDuringWindowReadCannotRebindOldRequestToNewConversation() = runBlocking {
        withService { service, drain, captures ->
            ServiceShadow.afterWindowRead = {
                ServiceShadow.afterWindowRead = null
                (field(service, "screenshotIdentityGeneration") as AtomicLong).incrementAndGet()
            }
            request(service)
            drain()
            assertEquals("窗口读取期间发生导航，必须丢弃旧请求", 0, captures())
        }
    }

    @Test fun notificationFallbackPassesPlatformToRealMediaFailureDiagnostics() = runBlocking {
        for ((pkg, platform) in listOf("com.tencent.mm" to "wechat", "com.ss.android.ugc.aweme" to "douyin")) {
            withService { service, drain, captures ->
                if (platform == "douyin") {
                    val snapshot = javaClass.getResourceAsStream("/capture/douyin-chat-40.6.0.json")!!.bufferedReader().use {
                        kotlinx.serialization.json.Json.decodeFromString<com.yuyan.imemodule.data.capture.ui.UiNodeSnapshot>(it.readText())
                    }
                    fun info(tree: com.yuyan.imemodule.data.capture.ui.UiNodeSnapshot): AccessibilityNodeInfo = AccessibilityNodeInfo.obtain().apply {
                        packageName = pkg; className = tree.className; text = tree.text; contentDescription = tree.contentDescription
                        viewIdResourceName = tree.viewId
                        setBoundsInScreen(Rect(tree.bounds.left, tree.bounds.top, tree.bounds.right, tree.bounds.bottom))
                        tree.children.forEach { Shadows.shadowOf(this).addChild(info(it)) }
                    }
                    ServiceShadow.root = info(snapshot)
                }
                val request = NotificationScreenshotFallbackRequest("synthetic", System.currentTimeMillis(), pkg)
                (field(service, "fallbackQueue") as NotificationScreenshotFallbackQueue).offer(request)
                diagnosticPlatforms.clear()
                service.javaClass.getDeclaredMethod("onStableFallbackRequest", NotificationScreenshotFallbackRequest::class.java)
                    .apply { isAccessible = true }.invoke(service, request)
                withTimeout(5_000) { while (captures() == 0) { Shadows.shadowOf(Looper.getMainLooper()).idle(); delay(10) } }
                delay(50)
                assertEquals(1, captures())
                assertEquals(listOf(platform), diagnosticPlatforms)
            }
        }
    }

    private fun field(service: PassiveChatAccessibilityService, name: String): Any =
        service.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(service)

    private fun request(service: PassiveChatAccessibilityService) {
        service.javaClass.getDeclaredMethod("captureEmptyTreeWeChatScreenshot", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(service, false)
    }

    private suspend fun withService(body: suspend (PassiveChatAccessibilityService, suspend () -> Unit, () -> Int) -> Unit) {
        val service = Robolectric.buildService(PassiveChatAccessibilityService::class.java).create().get()
        val scope = field(service, "backgroundScope") as CoroutineScope
        var captures = 0
        service.javaClass.getDeclaredField("mediaCapturer").apply { isAccessible = true }.set(service,
            WindowMediaCapturer(service, ScreenshotSource { _, _ -> captures++; WindowScreenshotResult.Failed(2) },
                onScreenshotResult = { platform, _, _ -> diagnosticPlatforms += platform }))
        val root = AccessibilityNodeInfo.obtain().apply {
            packageName = "com.tencent.mm"
            setBoundsInScreen(Rect(0, 0, 400, 800))
        }
        ServiceShadow.root = root
        ServiceShadow.reads.clear()
        val window = AccessibilityWindowInfo.obtain()
        Shadows.shadowOf(window).apply {
            setRoot(root); setId(root.windowId); setActive(true)
            setType(AccessibilityWindowInfo.TYPE_APPLICATION); setBoundsInScreen(Rect(0, 0, 400, 800))
        }
        Shadows.shadowOf(service).setWindows(listOf(window))
        CollectionConsent.setEnabled(service, true)
        // This fixture tests Binder threading while idle, not the input cooldown itself.
        // Robolectric resets its clock while the runtime singleton may survive another test.
        com.yuyan.imemodule.data.collect.ImageUploadRuntime.noteKeyActivity()
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(3001))
        assertTrue(com.yuyan.imemodule.data.collect.ImageUploadRuntime.isInputIdle())
        try {
            body(service, {
                withTimeout(5_000) {
                    do { Shadows.shadowOf(Looper.getMainLooper()).idle(); delay(10) }
                    while (scope.coroutineContext[Job]!!.children.any { it.isActive })
                }
            }, { captures })
        } finally {
            CollectionConsent.setEnabled(service, false)
            service.onDestroy()
            ServiceShadow.root = null
            ServiceShadow.afterWindowRead = null
            ServiceShadow.reads.clear()
        }
    }
}
