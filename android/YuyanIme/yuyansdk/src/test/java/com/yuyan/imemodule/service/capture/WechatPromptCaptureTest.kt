package com.yuyan.imemodule.service.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
    @Test fun newPromptEntryConfirmsWithIndependentFrameWhileOriginalPageRemainsVisible() = runBlocking {
        Harness(confirmNewTitle = true).use { h ->
            h.request(afterSend = false)
            h.until { h.pending.isNotEmpty() }
            assertEquals("pending", h.metadata(0).getString("conversation_identity_status"))
            val firstCaptureId = h.metadata(0).getString("screenshot_capture_id")
            val confirmed = withTimeoutOrNull(1_500) { h.until { h.pending.size >= 2 }; true } == true
            assertTrue("首图已落盘，同页应主动补独立确认帧，不能只等待两秒后的未知页探测", confirmed)
            assertEquals(2, h.requestNanos.size)
            assertEquals("confirmed", h.metadata(1).getString("conversation_identity_status"))
            assertEquals(firstCaptureId, h.metadata(1).getString("screenshot_capture_id"))
            assertEquals(h.pending.first().requiredAssetHashesJson, h.pending[1].requiredAssetHashesJson)
        }
    }

    @Test fun navigationAfterPendingPromptSaveCannotUseNewPageToConfirmOriginalImage() = runBlocking {
        Harness(confirmNewTitle = true).use { h ->
            h.onSaved = { if (h.pending.size == 1) h.resetNavigation() }
            h.request(afterSend = false)
            h.until { h.pending.isNotEmpty() && h.promptJob()?.isActive != true }
            ShadowSystemClock.advanceBy(Duration.ofSeconds(4))
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
            repeat(30) { Shadows.shadowOf(Looper.getMainLooper()).idle(); delay(10) }
            assertEquals("离页停止补确认，但保留已保存首图", 1, h.pending.size)
            assertEquals(1, h.requestNanos.size)
            assertEquals("pending", h.metadata(0).getString("conversation_identity_status"))
            assertEquals("原会话", h.metadata(0).getString("conversation_identity_observed_title"))
        }
    }

    @Test fun newConversationPromptDoesNotWaitForPreviousSavedImagesConfirmation() = runBlocking {
        Harness(confirmNewTitle = true).use { h ->
            h.request(afterSend = false)
            h.until { h.pending.isNotEmpty() }
            assertEquals("pending", h.metadata(0).getString("conversation_identity_status"))
            assertEquals(1, h.requestNanos.size)
            assertTrue("旧首图已经保存，但补确认仍在等待", h.promptJob()?.isActive == true)

            h.resetNavigation()
            h.request(afterSend = false)
            val accepted = withTimeoutOrNull(250) {
                h.until { h.frames.size >= 2 && h.frames[1].second.isAccepted }
                true
            } == true
            assertTrue("新会话短暂停留也应立即取帧，不能等旧图800ms补确认释放任务和截图锁", accepted)
            assertEquals("原会话", h.metadata(0).getString("conversation_identity_observed_title"))
            h.until { h.pending.size >= 2 }
            assertEquals("新会话", h.metadata(1).getString("conversation_identity_observed_title"))
            assertEquals("pending", h.metadata(0).getString("conversation_identity_status"))
        }
    }

    @Test fun acceptedPendingPromptFrameStillPersistsAfterNavigationBeforeTitleProcessing() = runBlocking {
        Harness(confirmNewTitle = true).use { h ->
            ImageUploadRuntime.noteKeyActivity()
            h.request(afterSend = false)
            h.until { h.frames.singleOrNull()?.second?.isAccepted == true }
            assertTrue(h.pending.isEmpty())
            h.resetNavigation()
            WechatListCaptureTest.ServiceShadow.root = AccessibilityNodeInfo.obtain().apply {
                packageName = "com.example.other"
            }
            ShadowSystemClock.advanceBy(Duration.ofSeconds(4))
            h.until { h.pending.isNotEmpty() && h.promptJob()?.isActive != true }
            assertEquals(1, h.pending.size)
            assertEquals(1, h.requestNanos.size)
            assertEquals("pending", h.metadata(0).getString("conversation_identity_status"))
            assertEquals("原会话", h.metadata(0).getString("conversation_identity_observed_title"))
            assertEquals(0, h.liveObservations.get())
        }
    }

    @Test fun confirmedPromptIdentityDoesNotNeedAnotherPhysicalFrame() = runBlocking {
        Harness().use { h ->
            h.request(afterSend = false)
            h.until { h.pending.isNotEmpty() && h.promptJob()?.isActive != true }
            assertEquals(1, h.requestNanos.size)
            assertEquals(1, h.pending.size)
            assertEquals("confirmed", h.metadata(0).getString("conversation_identity_status"))
        }
    }

    @Test fun screenshotCarriesFixedBodyReasonWithoutChangingItsFingerprint() = runBlocking {
        Harness().use { h ->
            h.request(afterSend = false)
            h.until { h.pending.size == 1 && h.promptJob()?.isActive != true }
            val first = h.pending.single()
            val metadata = org.json.JSONObject(first.payloadJson).getJSONObject("message").getJSONObject("metadata")
            assertEquals("body_boundary_unverified", metadata.optString("screenshot_body_reason"))
            assertTrue(com.yuyan.imemodule.data.capture.ScreenshotContentReason.entries.any {
                it.wireName == metadata.optString("screenshot_body_reason")
            })
            h.request(afterSend = false)
            h.until { h.pending.size == 2 && h.promptJob()?.isActive != true }
            assertEquals(first.fingerprint, h.pending.last().fingerprint)
        }
    }

    @Test fun failedFastSendGetsOneOrdinaryCaptureAfterInputStops() = runBlocking {
        Harness().use { h ->
            h.policy().confirm(h.windowId, h.generation())
            h.screenshotHook = { WindowScreenshotResult.Failed(SCREENSHOT_BACKGROUND_PAUSED) }
            ImageUploadRuntime.noteKeyActivity()
            h.request(afterSend = true)
            h.until { h.promptJob()?.isActive != true }
            assertEquals(0, h.ordinaryRequests.get())
            ShadowSystemClock.advanceBy(Duration.ofSeconds(4))
            h.until { h.pending.isNotEmpty() }
            assertEquals("快帧失败后同页空闲只补一次普通采集", 1, h.ordinaryRequests.get())
            assertEquals(1, h.pending.size)
        }
    }

    @Test fun failedFastSendRecoveryDoesNotFollowNavigation() = runBlocking {
        Harness().use { h ->
            h.policy().confirm(h.windowId, h.generation())
            h.screenshotHook = { WindowScreenshotResult.Failed(SCREENSHOT_BACKGROUND_PAUSED) }
            ImageUploadRuntime.noteKeyActivity()
            h.request(afterSend = true)
            h.until { h.promptJob()?.isActive != true }
            h.resetNavigation()
            ShadowSystemClock.advanceBy(Duration.ofSeconds(4))
            repeat(20) { Shadows.shadowOf(Looper.getMainLooper()).idle(); delay(10) }
            assertEquals(0, h.ordinaryRequests.get())
            assertTrue(h.pending.isEmpty())
        }
    }

    @Test fun successfulNewSendInvalidatesOlderIdleRecovery() = runBlocking {
        Harness().use { h ->
            h.policy().confirm(h.windowId, h.generation())
            h.screenshotHook = { WindowScreenshotResult.Failed(SCREENSHOT_BACKGROUND_PAUSED) }
            ImageUploadRuntime.noteKeyActivity()
            h.request(afterSend = true)
            h.until { h.promptJob()?.isActive != true }
            h.screenshotHook = { null }
            ImageUploadRuntime.noteKeyActivity()
            h.request(afterSend = true)
            h.until { h.frames.any { it.second.isAccepted } }
            ShadowSystemClock.advanceBy(Duration.ofSeconds(4))
            h.until { h.pending.size == 1 && h.promptJob()?.isActive != true }
            repeat(20) { Shadows.shadowOf(Looper.getMainLooper()).idle(); delay(10) }
            assertEquals("新发送成功后不能再执行旧发送的空闲补偿", 0, h.ordinaryRequests.get())
        }
    }

    @Test fun revokedConsentCancelsFailedSendIdleRecovery() = runBlocking {
        Harness().use { h ->
            h.policy().confirm(h.windowId, h.generation())
            h.screenshotHook = { WindowScreenshotResult.Failed(SCREENSHOT_BACKGROUND_PAUSED) }
            ImageUploadRuntime.noteKeyActivity()
            h.request(afterSend = true)
            h.until { h.promptJob()?.isActive != true }
            CollectionConsent.setEnabled(h.service, false)
            ShadowSystemClock.advanceBy(Duration.ofSeconds(4))
            repeat(20) { Shadows.shadowOf(Looper.getMainLooper()).idle(); delay(10) }
            assertEquals(0, h.ordinaryRequests.get())
            assertTrue(h.pending.isEmpty())
        }
    }

    @Test fun cancelledFastSendDoesNotStartIdleRecovery() = runBlocking {
        Harness().use { h ->
            ImageUploadRuntime.noteKeyActivity()
            h.request(afterSend = true)
            h.promptJob()?.cancelAndJoin()
            ShadowSystemClock.advanceBy(Duration.ofSeconds(4))
            repeat(20) { Shadows.shadowOf(Looper.getMainLooper()).idle(); delay(10) }
            assertEquals(0, h.ordinaryRequests.get())
            assertTrue(h.frames.isEmpty())
            assertTrue(h.pending.isEmpty())
        }
    }

    @Test fun explicitSendDoesNotWaitForPreviousPeerTypingCooldown() = runBlocking {
        Harness().use { h ->
            h.policy().observeTitle(h.windowId, h.generation(), "typing")
            assertTrue(h.policy().captureDelayMillis(ScreenshotScope(h.windowId, h.generation())) > 0)
            ImageUploadRuntime.noteKeyActivity()
            h.request(afterSend = true)
            h.until { h.frames.isNotEmpty() || h.promptJob()?.isActive != true }
            assertEquals("明确发送不能被之前的对方输入状态推迟三秒", 1, h.frames.size)
            h.until { h.frames.single().second.isAccepted }
        }
    }

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

    @Test fun contentNotificationDuringRequestedSendFrameDoesNotDiscardIt() = runBlocking {
        Harness().use { h ->
            h.screenshotHook = {
                // 与真机一致：渲染已稳定并开始取帧，回调前又收到同页content事件。
                (h.field("pendingSendRender") as SendRenderWait).changed()
                null
            }
            ImageUploadRuntime.noteKeyActivity()
            h.request(afterSend = true)
            h.until { h.frames.any { it.second.isAccepted } || h.promptJob()?.isActive != true }
            assertTrue("泛化content通知不能丢弃已经就绪的发送帧", h.frames.any { it.second.isAccepted })
            h.resetNavigation()
            ShadowSystemClock.advanceBy(Duration.ofSeconds(4))
            h.until { h.pending.size == 1 && h.promptJob()?.isActive != true }
            assertTrue(h.pending.single().payloadJson.contains("原会话"))
        }
    }

    @Test fun navigationBeforeSendCallbackStillRejectsFrame() = runBlocking {
        assertInFlightInvalidated { it.resetNavigation() }
    }

    @Test fun newInputBeforeSendCallbackStillRejectsFrame() = runBlocking {
        assertInFlightInvalidated { ImageUploadRuntime.noteKeyActivity() }
    }

    @Test fun revokedConsentBeforeSendCallbackStillRejectsFrame() = runBlocking {
        assertInFlightInvalidated { CollectionConsent.setEnabled(it.service, false) }
    }

    @Test fun gameBeforeSendCallbackStillRejectsFrame() = runBlocking {
        assertInFlightInvalidated { com.yuyan.imemodule.data.collect.GameWorkRuntime.setGaming(true) }
    }

    @Test fun newTouchBeforeSendCallbackStillRejectsFrame() = runBlocking {
        assertInFlightInvalidated { h ->
            val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START).apply {
                packageName = "com.tencent.mm"
            }
            h.service.onAccessibilityEvent(event)
            event.recycle()
        }
    }

    private suspend fun assertInFlightInvalidated(invalidate: (Harness) -> Unit) {
        Harness().use { h ->
            h.screenshotHook = { invalidate(h); null }
            ImageUploadRuntime.noteKeyActivity()
            h.request(afterSend = true)
            h.until { h.promptJob()?.isActive != true }
            assertTrue("必须真正覆盖系统请求已发出后的失效", h.frames.isNotEmpty())
            assertTrue(h.frames.none { it.second.isAccepted })
            assertTrue(h.frames.all { it.first.isRecycled })
            assertTrue(h.pending.isEmpty())
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

    private class Harness(private val confirmNewTitle: Boolean = false) : AutoCloseable {
        val service = Robolectric.buildService(PassiveChatAccessibilityService::class.java).create().get()
        val frames = CopyOnWriteArrayList<Pair<Bitmap, ChatCaptureAttempt>>()
        val requestNanos = CopyOnWriteArrayList<Long>()
        val scrollingAtRequest = CopyOnWriteArrayList<Boolean>()
        val pending = CopyOnWriteArrayList<PendingMessageEntity>()
        val ordinaryRequests = AtomicInteger()
        val encodes = AtomicInteger()
        val liveObservations = AtomicInteger()
        private val files = CopyOnWriteArrayList<String>()
        var screenshotHook: suspend () -> WindowScreenshotResult? = { null }
        var onSaved: () -> Unit = {}
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
                val attempt = currentCoroutineContext()[ChatCaptureAttempt]
                if (attempt == null) {
                    ordinaryRequests.incrementAndGet()
                    return@ScreenshotSource WindowScreenshotResult.Success(
                        screenshotBitmap(), 0, 0)
                }
                // 模拟真实WindowScreenshotter在物理调用紧前进入在途阶段。
                if (!attempt.beginFrameRequest()) return@ScreenshotSource WindowScreenshotResult.Failed(SCREENSHOT_BACKGROUND_PAUSED)
                screenshotHook()?.let { result ->
                    if (result !is WindowScreenshotResult.Success) attempt.frameRequestFailed()
                    return@ScreenshotSource result
                }
                val bitmap = screenshotBitmap()
                frames += bitmap to requireNotNull(currentCoroutineContext()[ChatCaptureAttempt])
                WindowScreenshotResult.Success(bitmap, 0, 0)
            }, encodeAsset = { _, _ -> encodes.incrementAndGet(); byteArrayOf(1, 2, 3) }))
            set("coordinator", CaptureCoordinator(store = object : CaptureOutboxStore {
                override suspend fun enqueueIfNew(seenMessage: SeenMessageEntity, pendingMessage: PendingMessageEntity,
                    pendingAssets: List<PendingAssetEntity>): Boolean {
                    files += pendingAssets.map { it.localPath }
                    pending += pendingMessage
                    onSaved()
                    return true
                }
            }, deviceId = { "test" }, wakeUploader = {}))
            set("screenshotIdentityResolver", object : ScreenshotConversationIdentityResolver {
                private var title = "原会话"
                private val tracker = WechatTitleStabilizer()
                private val observationTime = AtomicLong()
                override fun version(): Long = tracker.version()
                override fun reset() { title = "新会话"; tracker.reset() }
                override suspend fun resolve(asset: PendingAssetEntity, expectedVersion: Long,
                    titleInput: TitleOcrInput?): ScreenshotConversationIdentity {
                    liveObservations.incrementAndGet()
                    return if (confirmNewTitle) tracker.observe(title, titlePixels(title), observationTime.addAndGet(400), expectedVersion)
                    else identity(title)
                }
                override fun snapshot(keepCurrent: () -> Boolean): ScreenshotConversationIdentityResolver {
                    val frozenTitle = title
                    val frozen = tracker.fork()
                    return object : ScreenshotConversationIdentityResolver {
                        override fun version(): Long = frozen.version()
                        override suspend fun resolve(asset: PendingAssetEntity, expectedVersion: Long,
                            titleInput: TitleOcrInput?): ScreenshotConversationIdentity {
                            if (keepCurrent()) liveObservations.incrementAndGet()
                            return if (confirmNewTitle) observeSnapshotTitle(tracker, frozen, expectedVersion,
                                keepCurrent, frozenTitle, titlePixels(frozenTitle), observationTime.addAndGet(400))
                            else identity(frozenTitle)
                        }
                    }
                }
                private fun titlePixels(name: String) = (if (name == "原会话") "a" else "b").repeat(64)
                private fun identity(name: String) = screenshotConversationIdentity(name, "").copy(status = "confirmed", confidence = .95)
            })
        }

        private fun screenshotBitmap(): Bitmap = Bitmap.createBitmap(400, 800, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            if (confirmNewTitle) {
                val canvas = Canvas(this)
                val paint = Paint().apply { color = Color.rgb(247, 247, 247) }
                canvas.drawRect(0f, 746f, 400f, 800f, paint)
                paint.color = Color.BLACK
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2f
                canvas.drawCircle(23f, 772f, 11f, paint)
                canvas.drawCircle(378f, 772f, 11f, paint)
                canvas.drawLine(373f, 772f, 383f, 772f, paint)
                canvas.drawLine(378f, 767f, 378f, 777f, paint)
            }
        }

        fun metadata(index: Int): org.json.JSONObject = org.json.JSONObject(pending[index].payloadJson)
            .getJSONObject("message").getJSONObject("metadata")

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
