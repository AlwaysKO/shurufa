package com.yuyan.imemodule.service.capture

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.yuyan.imemodule.data.capture.CaptureCoordinator
import com.yuyan.imemodule.data.capture.CaptureOutboxStore
import com.yuyan.imemodule.data.capture.db.PendingAssetEntity
import com.yuyan.imemodule.data.capture.db.PendingMessageEntity
import com.yuyan.imemodule.data.capture.db.SeenMessageEntity
import com.yuyan.imemodule.data.capture.media.*
import com.yuyan.imemodule.data.collect.CollectionConsent
import com.yuyan.imemodule.data.collect.ImageUploadRuntime
import com.yuyan.imemodule.data.collect.resetGameWorkRuntimeForTest
import com.yuyan.imemodule.data.collect.resetImageInputForTest
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "mdpi", shadows = [WechatListCaptureTest.ServiceShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WechatPromptCaptureTest {
    @Test fun acceptedWechatSendFramePersistsOriginalConversationAfterNavigationWithoutChangingNewPage() = runBlocking {
        Harness().use { h ->
            ImageUploadRuntime.noteKeyActivity()
            h.request(afterSend = true)
            h.until { h.frames.singleOrNull()?.second?.isAccepted == true }
            assertEquals(0, h.encodes.get())
            assertTrue(h.pending.isEmpty())
            assertFalse(ImageUploadRuntime.isInputIdle())

            h.resetNavigation()
            WechatListCaptureTest.ServiceShadow.root = AccessibilityNodeInfo.obtain().apply {
                packageName = "com.example.other"
            }
            val newScope = ScreenshotScope(55, h.generation())
            h.policy().confirm(newScope.window, newScope.generation)
            ShadowSystemClock.advanceBy(Duration.ofSeconds(4))
            h.until { h.pending.size == 1 && h.promptJob()?.isActive != true }

            assertEquals(1, h.frames.size)
            assertEquals(1, h.encodes.get())
            assertTrue(h.pending.single().payloadJson.contains("原会话"))
            assertFalse(h.pending.single().payloadJson.contains("新会话"))
            assertEquals("导航后的旧帧仅使用独立标题状态", 0, h.liveObservations.get())
            assertTrue(h.policy().accepts(newScope.window, newScope.generation, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))
            assertFalse(h.policy().hasSavedContent(newScope))
            assertTrue(h.frames.single().first.isRecycled)
        }
    }

    @Test fun samePageSendCancelsEntryChildAndCapturesFinalFrameDespiteCandidateCooldown() = runBlocking {
        Harness().use { h ->
            ImageUploadRuntime.noteKeyActivity()
            h.request(afterSend = false)
            h.until { h.frames.singleOrNull()?.second?.isAccepted == true }
            val entryJob = requireNotNull(h.promptJob())
            val candidates = h.field("emptyCandidates") as EmptyTreeCandidateProbe
            assertFalse("首帧已消耗未确认候选，仍在两秒冷却中", candidates.canAttempt(ScreenshotScope(h.windowId, h.generation())))
            ImageUploadRuntime.noteKeyActivity()
            h.request(afterSend = true)
            h.until { h.frames.size == 2 && h.frames.last().second.isAccepted }
            assertTrue("替换必须取消微信独立截图子任务", entryJob.isCancelled)
            assertTrue("旧帧须释放位图及共享截图槽", h.frames.first().first.isRecycled)
            assertEquals(0, h.encodes.get())
            ShadowSystemClock.advanceBy(Duration.ofSeconds(4))
            h.until { h.pending.size == 1 && h.promptJob()?.isActive != true }
            assertEquals("仅保留发送后的最终帧", 1, h.encodes.get())
            assertEquals(2, h.frames.size)
        }
    }

    @Test fun sendDoesNotAcceptOldFrameBeforeHostRendersNewMessage() = runBlocking {
        Harness().use { h ->
            ImageUploadRuntime.noteKeyActivity()
            val startedAt = System.nanoTime()
            h.request(afterSend = true)
            h.until { h.frames.singleOrNull()?.second?.isAccepted == true }
            val elapsedMillis = (h.requestNanos.first() - startedAt) / 1_000_000
            assertTrue("消息渲染尚未给出正文更新信号时不能在早期锁定旧画面: $elapsedMillis", elapsedMillis >= 250)
        }
    }

    @Test fun sendFrameCanFollowAutomaticMessageScrollBeforeGlobalScrollSettles() = runBlocking {
        Harness().use { h ->
            ImageUploadRuntime.noteKeyActivity()
            h.request(afterSend = true)
            delay(230)
            val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_SCROLLED).apply {
                packageName = "com.tencent.mm"
                className = "android.widget.ListView"
            }
            h.service.onAccessibilityEvent(event)
            event.recycle()
            h.until { h.frames.singleOrNull()?.second?.isAccepted == true }
            assertTrue("发送气泡自动滚动不能重新要求普通滚动静止期",
                h.scrollingAtRequest.single())
        }
    }

    @Test fun nextUserTouchInvalidatesSendRenderBeforeScreenshot() = runBlocking {
        Harness().use { h ->
            ImageUploadRuntime.noteKeyActivity()
            h.request(afterSend = true)
            val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START).apply {
                packageName = "com.tencent.mm"
            }
            h.service.onAccessibilityEvent(event)
            event.recycle()
            h.until { h.promptJob()?.isActive != true }
            assertTrue("新手势不能沿用发送动画许可", h.frames.isEmpty())
        }
    }

    @Test fun continuousScrollingAfterSendWithoutTouchExplorationDoesNotCaptureIntermediateFrame() = runBlocking {
        Harness().use { h ->
            ImageUploadRuntime.noteKeyActivity()
            h.request(afterSend = true)
            repeat(10) {
                delay(60)
                val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_SCROLLED).apply {
                    packageName = "com.tencent.mm"
                    className = "android.widget.ListView"
                }
                h.service.onAccessibilityEvent(event)
                event.recycle()
                Shadows.shadowOf(Looper.getMainLooper()).idle()
            }
            h.until { h.promptJob()?.isActive != true }
            assertTrue("普通触屏没有探索事件时，持续滚动也不能由发送许可放行", h.frames.isEmpty())
        }
    }

    @Test fun layoutChangeBeforeFirstFrameAllowsOneRetryWithinSameSend() = runBlocking {
        Harness().use { h ->
            var first = true
            h.screenshotHook = {
                if (first) {
                    first = false
                    (h.field("pendingSendRender") as SendRenderWait).changed()
                    WindowScreenshotResult.Failed(SCREENSHOT_BACKGROUND_PAUSED)
                } else null
            }
            ImageUploadRuntime.noteKeyActivity()
            h.request(afterSend = true)
            h.until { h.frames.singleOrNull()?.second?.isAccepted == true || h.promptJob()?.isActive != true }
            assertEquals("布局变化丢弃的首帧不能终止整次发送", 2, h.requestNanos.size)
            assertTrue(h.frames.single().second.isAccepted)
        }
    }

    private class Harness : AutoCloseable {
        val service = Robolectric.buildService(PassiveChatAccessibilityService::class.java).create().get()
        val frames = CopyOnWriteArrayList<Pair<Bitmap, ChatCaptureAttempt>>()
        val requestNanos = CopyOnWriteArrayList<Long>()
        val scrollingAtRequest = CopyOnWriteArrayList<Boolean>()
        val pending = CopyOnWriteArrayList<PendingMessageEntity>()
        val encodes = AtomicInteger()
        val liveObservations = AtomicInteger()
        private val files = CopyOnWriteArrayList<String>()
        var screenshotHook: suspend () -> WindowScreenshotResult? = { null }
        val windowId: Int

        init {
            resetImageInputForTest(); resetGameWorkRuntimeForTest()
            CollectionConsent.setEnabled(service, true)
            val root = AccessibilityNodeInfo.obtain().apply {
                packageName = "com.tencent.mm"; className = "android.widget.FrameLayout"
                setBoundsInScreen(Rect(0, 0, 400, 800))
            }
            windowId = root.windowId
            WechatListCaptureTest.ServiceShadow.root = root
            val window = AccessibilityWindowInfo.obtain()
            Shadows.shadowOf(window).apply {
                setRoot(root); setId(windowId); setActive(true)
                setType(AccessibilityWindowInfo.TYPE_APPLICATION); setBoundsInScreen(Rect(0, 0, 400, 800))
            }
            Shadows.shadowOf(service).setWindows(listOf(window))
            set("mediaCapturer", WindowMediaCapturer(service, ScreenshotSource { _, _ ->
                requestNanos += System.nanoTime()
                scrollingAtRequest += (field("scrollGate") as ScrollCaptureGate).isScrolling()
                screenshotHook()?.let { return@ScreenshotSource it }
                val bitmap = Bitmap.createBitmap(400, 800, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
                frames += bitmap to requireNotNull(currentCoroutineContext()[ChatCaptureAttempt])
                WindowScreenshotResult.Success(bitmap, 0, 0)
            }, encodeAsset = { _, _ -> encodes.incrementAndGet(); byteArrayOf(1, 2, 3) }))
            set("coordinator", CaptureCoordinator(store = object : CaptureOutboxStore {
                override suspend fun enqueueIfNew(seenMessage: SeenMessageEntity, pendingMessage: PendingMessageEntity,
                    pendingAssets: List<PendingAssetEntity>): Boolean {
                    files += pendingAssets.map { it.localPath }
                    pending += pendingMessage
                    return true
                }
            }, deviceId = { "test" }, wakeUploader = {}))
            set("screenshotIdentityResolver", object : ScreenshotConversationIdentityResolver {
                private var title = "原会话"
                override fun reset() { title = "新会话" }
                override suspend fun resolve(asset: PendingAssetEntity, expectedVersion: Long,
                    titleInput: TitleOcrInput?): ScreenshotConversationIdentity {
                    liveObservations.incrementAndGet()
                    return identity(title)
                }
                override fun snapshot(keepCurrent: () -> Boolean): ScreenshotConversationIdentityResolver {
                    val frozenTitle = title
                    return object : ScreenshotConversationIdentityResolver {
                        override suspend fun resolve(asset: PendingAssetEntity, expectedVersion: Long,
                            titleInput: TitleOcrInput?): ScreenshotConversationIdentity {
                            if (keepCurrent()) liveObservations.incrementAndGet()
                            return identity(frozenTitle)
                        }
                    }
                }
                private fun identity(name: String) = screenshotConversationIdentity(name, "").copy(status = "confirmed", confidence = .95)
            })
        }

        fun field(name: String): Any? = service.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(service)
        private fun set(name: String, value: Any) { service.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(service, value) }
        fun generation(): Long = (field("screenshotIdentityGeneration") as AtomicLong).get()
        fun policy(): ScreenshotUpdatePolicy = field("screenshotUpdates") as ScreenshotUpdatePolicy
        fun promptJob(): Job? = field("promptCaptureJob") as? Job
        fun request(afterSend: Boolean) {
            service.javaClass.getDeclaredMethod("requestPromptChatCapture", String::class.java, Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(service, "com.tencent.mm", afterSend)
        }
        fun resetNavigation() { service.javaClass.getDeclaredMethod("resetScreenshotIdentity").apply { isAccessible = true }.invoke(service) }
        suspend fun until(condition: () -> Boolean) = withTimeout(5_000) {
            while (!condition()) { Shadows.shadowOf(Looper.getMainLooper()).idle(); delay(10) }
        }
        override fun close() {
            CollectionConsent.setEnabled(service, false)
            service.onDestroy()
            WechatListCaptureTest.ServiceShadow.root = null
            files.forEach { File(it).delete() }
            resetImageInputForTest(); resetGameWorkRuntimeForTest()
        }
    }
}
