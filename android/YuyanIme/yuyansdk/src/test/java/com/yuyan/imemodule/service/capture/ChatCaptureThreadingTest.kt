package com.yuyan.imemodule.service.capture

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.yuyan.imemodule.data.collect.CollectionConsent
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
    @Implements(AccessibilityService::class)
    class ServiceShadow : ShadowAccessibilityService() {
        @Implementation
        fun getRootInActiveWindow(): AccessibilityNodeInfo? = root
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
}
