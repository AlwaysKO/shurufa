package com.yuyan.imemodule.service.capture

import android.accessibilityservice.AccessibilityService
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAccessibilityService
import android.graphics.Color
import android.graphics.Rect
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.yuyan.imemodule.data.capture.*
import com.yuyan.imemodule.data.capture.db.*
import com.yuyan.imemodule.data.capture.media.*
import com.yuyan.imemodule.data.capture.ui.IntRect
import com.yuyan.imemodule.data.capture.ui.UiNodeSnapshot
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
@Config(sdk = [30], qualifiers = "mdpi", shadows = [WechatListCaptureTest.ServiceShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WechatListCaptureTest {
    @Implements(AccessibilityService::class)
    class ServiceShadow : ShadowAccessibilityService() {
        @Implementation fun getRootInActiveWindow(): AccessibilityNodeInfo? = root?.let { AccessibilityNodeInfo.obtain(it) }
        companion object { var root: AccessibilityNodeInfo? = null }
    }
    @Test fun rawPixelsDeduplicateTreeOcrAndNotificationEntriesButKeepChangedRows() = runBlocking {
        val service = Robolectric.buildService(PassiveChatAccessibilityService::class.java).create().get()
        fun set(name: String, value: Any) = service.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
        fun get(name: String): Any = requireNotNull(service.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(service))
        val scope = get("backgroundScope") as CoroutineScope
        val queue = get("fallbackQueue") as NotificationScreenshotFallbackQueue
        val seen = mutableSetOf<String>()
        val pending = mutableListOf<PendingMessageEntity>()
        val files = mutableSetOf<String>()
        var captures = 0
        var variant = 0
        val store = object : CaptureOutboxStore {
            override suspend fun enqueueIfNew(seenMessage: SeenMessageEntity, pendingMessage: PendingMessageEntity,
                pendingAssets: List<PendingAssetEntity>): Boolean {
                files += pendingAssets.map { it.localPath }
                if (!seen.add(seenMessage.fingerprint)) return false
                pending += pendingMessage
                return true
            }
        }
        val capturer = WindowMediaCapturer(service, ScreenshotSource { _, _ ->
            captures++
            val image = wechatListSample()
            // 导航变化始终不同，列表最后一个像素直到 variant=4 才改变。
            image.setPixel(265, 752, if (variant % 2 == 0) Color.RED else Color.BLUE)
            if (variant >= 4) image.setPixel(180, 743, if (variant == 5) Color.rgb(254, 254, 254) else Color.RED)
            WindowScreenshotResult.Success(image, 0, 0)
        })
        val recordingCapturer = MediaAssetCapturer { window, bounds, requests ->
            capturer.capture(window, bounds, requests).also { assets -> files += assets.values.map { it.localPath } }
        }
        val statusId = service.resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBottom = service.resources.getDimensionPixelSize(statusId)
        fun worker() = CaptureCoordinator(store = store, deviceId = { "device" }, wakeUploader = {},
            mediaCapturer = recordingCapturer, wechatListContentInput = { bounds ->
                val band = wechatTitleBand(statusBottom, bounds.top, 1f)
                ScreenshotContentInput(band.top + band.height, detectWechatList = true)
            })
        fun root(readable: Boolean) = AccessibilityNodeInfo.obtain().apply {
            packageName = "com.tencent.mm"; className = "android.widget.FrameLayout"
            setBoundsInScreen(Rect(0, 0, 400, 800))
            if (readable) Shadows.shadowOf(this).addChild(AccessibilityNodeInfo.obtain().apply {
                packageName = "com.tencent.mm"; text = "微信"; viewIdResourceName = "com.tencent.mm:id/title"
                className = "android.widget.TextView"; setBoundsInScreen(Rect(150, 25, 250, 65))
            })
        }
        fun setRoot(readable: Boolean) {
            val root = root(readable)
            ServiceShadow.root = root
            val window = AccessibilityWindowInfo.obtain()
            Shadows.shadowOf(window).apply {
                setRoot(root); setId(root.windowId); setActive(true)
                setType(AccessibilityWindowInfo.TYPE_APPLICATION); setBoundsInScreen(Rect(0, 0, 400, 800))
            }
            Shadows.shadowOf(service).setWindows(listOf(window))
        }
        suspend fun drain() {
            val job = scope.coroutineContext[Job]!!.children.firstOrNull()
            withTimeout(10_000) {
                do { Shadows.shadowOf(Looper.getMainLooper()).idle(); delay(10) } while (job?.isActive == true)
            }
        }
        suspend fun notification(readable: Boolean) {
            setRoot(readable)
            val request = NotificationScreenshotFallbackRequest("list-$variant", System.currentTimeMillis())
            queue.offer(request)
            service.javaClass.getDeclaredMethod("onStableFallbackRequest", NotificationScreenshotFallbackRequest::class.java)
                .apply { isAccessible = true }.invoke(service, request)
            drain()
            assertNull(queue.peek())
        }
        set("coordinator", worker())
        set("mediaCapturer", capturer)
        set("screenshotIdentityResolver", object : ScreenshotConversationIdentityResolver {
            override suspend fun resolve(asset: PendingAssetEntity, expectedVersion: Long, titleInput: TitleOcrInput?): ScreenshotConversationIdentity {
                files += asset.localPath
                assertNotNull(titleInput)
                return WechatTitleStabilizer().observe("微信", null, 0).copy(exactTitleHash = "a".repeat(64))
            }
        })
        CollectionConsent.setEnabled(service, true)
        try {
            val snapshot = UiNodeSnapshot(null, "root", null, null, IntRect(0, 0, 400, 800), listOf(
                UiNodeSnapshot("com.tencent.mm:id/title", "android.widget.TextView", "微信", null, IntRect(150, 25, 250, 65), emptyList())))
            worker().capture("com.tencent.mm", snapshot, 1)
            assertEquals(1, pending.size)
            assertTrue(pending.single().payloadJson.contains(WECHAT_LIST_HASH))
            variant = 1; setRoot(false)
            service.javaClass.getDeclaredMethod("captureEmptyTreeWeChatScreenshot", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(service, false)
            drain()
            assertEquals("空树 OCR 应复用列表正文指纹", 1, pending.size)
            val preferences = service.getSharedPreferences("notification_screenshot_fallback", 0)
            val firstOcrAsset = preferences.getString("last_empty_tree_screenshot_sha256", null)
            assertNotNull(firstOcrAsset)
            variant = 2; notification(false)
            assertEquals("通知空树补拍应复用列表正文指纹", 1, pending.size)
            variant = 3; notification(true)
            assertEquals("通知可读树补拍应复用列表正文指纹", 1, pending.size)
            variant = 4; set("coordinator", worker()); notification(true)
            assertEquals("正文最后一行微小变化必须保留", 2, pending.size)
            variant = 5; setRoot(false)
            service.javaClass.getDeclaredMethod("captureEmptyTreeWeChatScreenshot", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(service, false)
            drain()
            assertEquals("夹具必须覆盖相同有损编码", firstOcrAsset,
                preferences.getString("last_empty_tree_screenshot_sha256", null))
            assertEquals("有损编码相同也不能吞掉原始列表像素变化", 3, pending.size)
            assertEquals(6, captures)
        } finally {
            CollectionConsent.setEnabled(service, false); service.onDestroy()
            ServiceShadow.root = null
            files.forEach { File(it).delete() }
        }
    }
}
