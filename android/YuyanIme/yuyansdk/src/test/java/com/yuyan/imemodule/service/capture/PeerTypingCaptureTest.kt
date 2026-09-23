package com.yuyan.imemodule.service.capture

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import com.yuyan.imemodule.data.capture.CaptureCoordinator
import com.yuyan.imemodule.data.capture.CaptureOutboxStore
import com.yuyan.imemodule.data.capture.db.PendingAssetEntity
import com.yuyan.imemodule.data.capture.db.PendingMessageEntity
import com.yuyan.imemodule.data.capture.db.SeenMessageEntity
import com.yuyan.imemodule.data.capture.media.*
import com.yuyan.imemodule.data.collect.CollectionConsent
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], shadows = [ChatCaptureThreadingTest.ServiceShadow::class, ChatCaptureThreadingTest.NodeShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PeerTypingCaptureTest {
    @Test fun notificationTypingIsBlockedAndEmptyTreeUsesOnlyTheExactHeader() = runBlocking {
        val service = Robolectric.buildService(PassiveChatAccessibilityService::class.java).create().get()
        fun set(name: String, value: Any) = service.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
        fun get(name: String): Any = requireNotNull(service.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(service))
        val scope = get("backgroundScope") as CoroutineScope
        val queue = get("fallbackQueue") as NotificationScreenshotFallbackQueue
        var captures = 0
        var enqueued = 0
        val headers = mutableListOf<Pair<Boolean, Int?>>()
        val files = mutableSetOf<String>()
        fun node(id: String?, text: String?, bounds: Rect, clazz: String) = AccessibilityNodeInfo.obtain().apply {
            packageName = "com.tencent.mm"; viewIdResourceName = id; this.text = text; className = clazz
            setBoundsInScreen(bounds)
        }
        fun root(title: String?): AccessibilityNodeInfo = node(null, null, Rect(0, 0, 1080, 1920), "android.widget.FrameLayout").apply {
            if (title != null) {
                Shadows.shadowOf(this).addChild(node("com.tencent.mm:id/chatting_title", title, Rect(180, 50, 850, 130), "android.widget.TextView"))
                Shadows.shadowOf(this).addChild(node("com.tencent.mm:id/chatting_content_et", null, Rect(80, 1650, 850, 1760), "android.widget.EditText"))
            }
        }
        suspend fun runRequest(request: NotificationScreenshotFallbackRequest) {
            queue.offer(request)
            service.javaClass.getDeclaredMethod("onStableFallbackRequest", NotificationScreenshotFallbackRequest::class.java)
                .apply { isAccessible = true }.invoke(service, request)
            val requestJob = scope.coroutineContext[Job]!!.children.firstOrNull()
            withTimeout(5_000) {
                do {
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    delay(10)
                } while (requestJob?.isActive == true)
            }
            queue.removeIfSame(request)
        }
        CollectionConsent.setEnabled(service, true)
        ChatCaptureThreadingTest.NodeShadow.onRead = null
        set("coordinator", CaptureCoordinator(store = object : CaptureOutboxStore {
            override suspend fun enqueueIfNew(seenMessage: SeenMessageEntity, pendingMessage: PendingMessageEntity,
                pendingAssets: List<PendingAssetEntity>): Boolean {
                enqueued++
                return true
            }
        }, deviceId = { "test-device" }, wakeUploader = {}))
        set("mediaCapturer", WindowMediaCapturer(service, ScreenshotSource { _, _ ->
            captures++
            WindowScreenshotResult.Success(Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888), 0, 0)
        }))
        set("screenshotIdentityResolver", object : ScreenshotConversationIdentityResolver {
            override suspend fun resolve(asset: PendingAssetEntity, expectedVersion: Long, titleInput: TitleOcrInput?): ScreenshotConversationIdentity {
                files += asset.localPath
                val header = titleInput?.takeOrDecode(asset.localPath)
                headers += (titleInput?.hasExactTitleBand == true) to header?.height
                header?.recycle()
                return unresolvedWechatScreenshotIdentity("对方正在输入..8")
            }
        })
        try {
            for (title in listOf("对方正在输入", "对方正在输入.", "对方正在输入..8")) {
                ChatCaptureThreadingTest.ServiceShadow.root = root(title)
                runRequest(NotificationScreenshotFallbackRequest(title, System.currentTimeMillis()))
            }
            assertEquals("页面树明确为输入状态时不能退回截图", 0, captures)
            ChatCaptureThreadingTest.ServiceShadow.root = root(null)
            runRequest(NotificationScreenshotFallbackRequest("empty-tree", System.currentTimeMillis()))
            assertEquals(1, captures)
            assertEquals(listOf(true to wechatTitleBand(0, 0, service.resources.displayMetrics.density).height), headers)
            assertEquals("输入状态截图不能进入上报队列", 0, enqueued)
            // 被丢弃的状态帧不能更新已上报图片缓存。
            assertFalse(service.getSharedPreferences("notification_screenshot_fallback", 0).contains("last_screenshot_sha256"))
        } finally {
            CollectionConsent.setEnabled(service, false)
            service.onDestroy()
            ChatCaptureThreadingTest.ServiceShadow.root = null
            files.forEach { File(it).delete() }
        }
    }
}
