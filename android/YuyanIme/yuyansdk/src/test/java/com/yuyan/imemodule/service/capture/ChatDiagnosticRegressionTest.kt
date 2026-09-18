package com.yuyan.imemodule.service.capture

import com.yuyan.imemodule.data.collect.CollectionConsent
import com.yuyan.imemodule.data.capture.ui.IntRect
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Rect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [ChatCaptureThreadingTest.ServiceShadow::class, ChatCaptureThreadingTest.NodeShadow::class])
class ChatDiagnosticRegressionTest {
    @Test fun immediateProbeMustNotCancelPendingAccessibilityEvent() {
        val service = Robolectric.buildService(PassiveChatAccessibilityService::class.java).create().get()
        CollectionConsent.setEnabled(service, true)
        val generation = service.javaClass.getDeclaredField("snapshotGeneration").apply { isAccessible = true }.get(service) as AtomicLong
        generation.set(42)
        try {
            service.javaClass.getDeclaredMethod("captureCurrentForegroundViewport", String::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }
                .invoke(service, "com.tencent.mm", 0)
            assertEquals("0ms补探测不能作废原事件的空树截图任务", 42L, generation.get())
        } finally { CollectionConsent.setEnabled(service, false); service.onDestroy() }
    }
    @Test fun queuedEntryProbeSurvivesUnrelatedContentReadGeneration() {
        val service = Robolectric.buildService(PassiveChatAccessibilityService::class.java).create().get()
        CollectionConsent.setEnabled(service, true)
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        val scope = service.javaClass.getDeclaredField("backgroundScope").apply { isAccessible = true }.get(service) as CoroutineScope
        val generation = service.javaClass.getDeclaredField("snapshotGeneration").apply { isAccessible = true }.get(service) as AtomicLong
        ChatCaptureThreadingTest.NodeShadow.read = CountDownLatch(1)
        ChatCaptureThreadingTest.NodeShadow.onRead = null
        ChatCaptureThreadingTest.ServiceShadow.root = AccessibilityNodeInfo.obtain().apply {
            packageName = "com.tencent.mm"; className = "android.widget.FrameLayout"
            setBoundsInScreen(Rect(0, 0, 1080, 2200))
        }
        try {
            scope.launch { started.countDown(); release.await(3, TimeUnit.SECONDS) }
            assertTrue(started.await(3, TimeUnit.SECONDS))
            service.javaClass.getDeclaredMethod("captureCurrentForegroundViewport", String::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(service, "com.tencent.mm", 0)
            generation.incrementAndGet()
            release.countDown()
            assertTrue("没有导航切换时，普通内容事件不能饿死进入会话探测", ChatCaptureThreadingTest.NodeShadow.read.await(3, TimeUnit.SECONDS))
        } finally {
            release.countDown(); CollectionConsent.setEnabled(service, false); service.onDestroy()
            ChatCaptureThreadingTest.ServiceShadow.root = null
        }
    }
    @Test fun windowCaptureKeepsWholeVisibleChatWithoutKeyboard() {
        val window = IntRect(0, 90, 1080, 2200)
        assertEquals(window, emptyTreeScreenshotBounds(window, null))
    }
    @Test fun onlyValidKeyboardBoundaryCutsBottomNotTitle() {
        val window = IntRect(0, 90, 1080, 2200)
        assertEquals(IntRect(0, 90, 1080, 1500), emptyTreeScreenshotBounds(window, 1500))
        assertEquals(window, emptyTreeScreenshotBounds(window, 0))
        assertEquals(window, emptyTreeScreenshotBounds(window, 90))
        assertEquals(window, emptyTreeScreenshotBounds(window, 2500))
    }
}
