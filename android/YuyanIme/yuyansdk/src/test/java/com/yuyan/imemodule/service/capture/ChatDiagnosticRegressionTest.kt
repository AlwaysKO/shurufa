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
    @Test fun confirmedEmptyChatContentSchedulesFollowupInsteadOfRejectingPage() {
        val service = Robolectric.buildService(PassiveChatAccessibilityService::class.java).create().get()
        CollectionConsent.setEnabled(service, true)
        val root = AccessibilityNodeInfo.obtain().apply {
            packageName = "com.tencent.mm"; className = "android.widget.FrameLayout"
            setBoundsInScreen(Rect(0, 0, 1080, 2200))
        }
        ChatCaptureThreadingTest.ServiceShadow.root = root
        ChatCaptureThreadingTest.NodeShadow.onRead = null
        try {
            val policy = service.javaClass.getDeclaredField("screenshotUpdates").apply { isAccessible = true }.get(service) as ScreenshotUpdatePolicy
            policy.confirm(root.windowId, 0)
            org.robolectric.shadows.ShadowLog.clear()
            val event = android.view.accessibility.AccessibilityEvent.obtain(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply {
                packageName = "com.tencent.mm"; className = "android.widget.FrameLayout"
            }
            service.onAccessibilityEvent(event)
            event.recycle()
            val scope = service.javaClass.getDeclaredField("backgroundScope").apply { isAccessible = true }.get(service) as CoroutineScope
            kotlinx.coroutines.runBlocking { scope.launch {}.join() }
            val logs = org.robolectric.shadows.ShadowLog.getLogsForTag("ChatCaptureTrace").map { it.msg }
            assertTrue(logs.toString(), logs.any { it.contains("stage=CONTENT_SCHEDULED") })
            assertFalse(logs.any { it.contains("stage=PAGE_REJECTED") })
        } finally {
            CollectionConsent.setEnabled(service, false); service.onDestroy()
            ChatCaptureThreadingTest.ServiceShadow.root = null
        }
    }

    @Test fun contentDuringFirstIdentityConfirmationLeavesOneFinalCheck() {
        val service = Robolectric.buildService(PassiveChatAccessibilityService::class.java).create().get()
        CollectionConsent.setEnabled(service, true)
        val root = AccessibilityNodeInfo.obtain().apply {
            packageName = "com.tencent.mm"; className = "android.widget.FrameLayout"
            setBoundsInScreen(Rect(0, 0, 1080, 2200))
        }
        ChatCaptureThreadingTest.ServiceShadow.root = root
        ChatCaptureThreadingTest.NodeShadow.onRead = null
        try {
            val policy = service.javaClass.getDeclaredField("screenshotUpdates").apply { isAccessible = true }.get(service) as ScreenshotUpdatePolicy
            val gate = service.javaClass.getDeclaredField("screenshotGate").apply { isAccessible = true }.get(service) as ScreenshotRequestGate
            val captureScope = ScreenshotScope(root.windowId, 0)
            assertTrue(gate.offer(captureScope))
            org.robolectric.shadows.ShadowLog.clear()
            val event = android.view.accessibility.AccessibilityEvent.obtain(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply {
                packageName = "com.tencent.mm"; className = "android.widget.FrameLayout"
            }
            service.onAccessibilityEvent(event)
            event.recycle()
            val scope = service.javaClass.getDeclaredField("backgroundScope").apply { isAccessible = true }.get(service) as CoroutineScope
            kotlinx.coroutines.runBlocking { scope.launch {}.join() }
            val logs = org.robolectric.shadows.ShadowLog.getLogsForTag("ChatCaptureTrace").map { it.msg }
            // 初次确认期间不能直接调度未验证页；确认完成后只补一次末次状态。
            assertFalse(logs.any { it.contains("stage=CONTENT_SCHEDULED") })
            policy.confirm(root.windowId, 0)
            assertEquals(captureScope, gate.complete(confirmed = captureScope))
            assertFalse(logs.any { it.contains("stage=PAGE_REJECTED") })
        } finally {
            CollectionConsent.setEnabled(service, false); service.onDestroy()
            ChatCaptureThreadingTest.ServiceShadow.root = null
        }
    }

    @Test fun navigationClearsEligibilityAndPendingContentBeforeDelayedCapture() {
        val service = Robolectric.buildService(PassiveChatAccessibilityService::class.java).create().get()
        CollectionConsent.setEnabled(service, true)
        try {
            val policy = service.javaClass.getDeclaredField("screenshotUpdates").apply { isAccessible=true }.get(service) as ScreenshotUpdatePolicy
            val gate = service.javaClass.getDeclaredField("screenshotGate").apply { isAccessible=true }.get(service) as ScreenshotRequestGate
            val scope = ScreenshotScope(10,0)
            policy.confirm(10,0); gate.offer(scope); gate.offer(scope)
            service.onInterrupt()
            assertFalse(policy.accepts(10,0,android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))
            assertNull(gate.complete(confirmed=scope))
            org.robolectric.shadows.ShadowLog.clear()
            service.javaClass.getDeclaredMethod("captureScreenshotInScope",ScreenshotScope::class.java).apply { isAccessible=true }.invoke(service,scope)
            assertFalse(org.robolectric.shadows.ShadowLog.getLogsForTag("ChatCaptureTrace").any { it.msg.contains("stage=EMPTY_REQUEST") })
        } finally { CollectionConsent.setEnabled(service,false); service.onDestroy() }
    }
    @Test fun immediateProbeMustNotCancelPendingAccessibilityEvent() {
        val service = Robolectric.buildService(PassiveChatAccessibilityService::class.java).create().get()
        CollectionConsent.setEnabled(service, true)
        val generation = service.javaClass.getDeclaredField("snapshotGeneration").apply { isAccessible = true }.get(service) as AtomicLong
        generation.set(42)
        try {
            service.javaClass.getDeclaredMethod("captureCurrentForegroundViewport", String::class.java, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
                .invoke(service, "com.tencent.mm", 0, false)
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
            service.javaClass.getDeclaredMethod("captureCurrentForegroundViewport", String::class.java, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(service, "com.tencent.mm", 0, false)
            generation.incrementAndGet()
            release.countDown()
            assertTrue("没有导航切换时，普通内容事件不能饿死进入会话探测", ChatCaptureThreadingTest.NodeShadow.read.await(3, TimeUnit.SECONDS))
        } finally {
            release.countDown(); CollectionConsent.setEnabled(service, false); service.onDestroy()
            ChatCaptureThreadingTest.ServiceShadow.root = null
        }
    }
    @Test fun systemStatusBarIsExcludedWithoutCuttingTitleOrLastMessage() {
        assertEquals(IntRect(0,126,1200,2664), emptyTreeScreenshotBounds(IntRect(0,0,1200,2664),null,126))
        assertEquals(IntRect(0,126,1200,2664), emptyTreeScreenshotBounds(IntRect(0,126,1200,2664),null,126))
        assertEquals(IntRect(0,126,1200,1800), emptyTreeScreenshotBounds(IntRect(0,0,1200,2664),1800,126))
        assertEquals(IntRect(0,0,1200,2664), emptyTreeScreenshotBounds(IntRect(0,0,1200,2664),null,3000))
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
