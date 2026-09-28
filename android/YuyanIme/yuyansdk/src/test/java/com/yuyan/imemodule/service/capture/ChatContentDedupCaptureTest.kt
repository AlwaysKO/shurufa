package com.yuyan.imemodule.service.capture

import android.accessibilityservice.AccessibilityService
import android.graphics.*
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.yuyan.imemodule.data.capture.*
import com.yuyan.imemodule.data.capture.db.*
import com.yuyan.imemodule.data.capture.media.*
import com.yuyan.imemodule.data.capture.model.ConversationType
import com.yuyan.imemodule.data.collect.CollectionConsent
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowAccessibilityService
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "mdpi", shadows = [ChatContentDedupCaptureTest.ServiceShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatContentDedupCaptureTest {
    @Implements(AccessibilityService::class)
    class ServiceShadow : ShadowAccessibilityService() {
        @Implementation fun getRootInActiveWindow(): AccessibilityNodeInfo? = root?.let { AccessibilityNodeInfo.obtain(it) }
        companion object { var root: AccessibilityNodeInfo? = null }
    }

    @Test fun confirmedEmptyTreeAndNotificationUseBodyHistoryWhileKeepingNewMessages() = runBlocking {
        val service = Robolectric.buildService(PassiveChatAccessibilityService::class.java).create().get()
        fun set(name: String, value: Any) = service.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
        fun get(name: String): Any = requireNotNull(service.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(service))
        val scope = get("backgroundScope") as CoroutineScope
        val pending = mutableListOf<PendingMessageEntity>()
        val files = mutableSetOf<String>()
        val assetHashes = mutableListOf<String>()
        var variant = 0
        var offset = 0
        var height = 800
        var identity = ScreenshotConversationIdentity("screenshot-v2:chat-a", "测试联系人", ConversationType.DIRECT,
            .95, "wechat_ocr_title", "confirmed", "测试联系人", exactTitleHash = "a".repeat(64))
        val store = object : CaptureOutboxStore {
            override suspend fun enqueueIfNew(seenMessage: SeenMessageEntity, pendingMessage: PendingMessageEntity,
                pendingAssets: List<PendingAssetEntity>): Boolean {
                pending += pendingMessage
                return true // 不借数据库的相同附件判重掩盖内容历史缺失。
            }
        }
        val capturer = WindowMediaCapturer(service, ScreenshotSource { _, _ ->
            WindowScreenshotResult.Success(sample(height, offset, variant), 0, 0)
        })
        val recording = MediaAssetCapturer { window, bounds, requests -> capturer.capture(window, bounds, requests).also {
            files += it.values.map { asset -> asset.localPath }
        } }
        val worker = CaptureCoordinator(store = store, deviceId = { "device" }, wakeUploader = {}, mediaCapturer = recording)
        set("coordinator", worker); set("mediaCapturer", capturer)
        set("screenshotIdentityResolver", object : ScreenshotConversationIdentityResolver {
            override suspend fun resolve(asset: PendingAssetEntity, expectedVersion: Long, titleInput: TitleOcrInput?): ScreenshotConversationIdentity {
                files += asset.localPath
                assetHashes += asset.sha256
                return identity
            }
        })
        fun setWindow() {
            val root = AccessibilityNodeInfo.obtain().apply {
                packageName = "com.tencent.mm"; className = "android.widget.FrameLayout"
                setBoundsInScreen(Rect(0, 0, 360, height))
            }
            ServiceShadow.root = root
            val window = AccessibilityWindowInfo.obtain()
            Shadows.shadowOf(window).apply {
                setRoot(root); setId(root.windowId); setActive(true)
                setType(AccessibilityWindowInfo.TYPE_APPLICATION); setBoundsInScreen(Rect(0, 0, 360, height))
            }
            Shadows.shadowOf(service).setWindows(listOf(window))
        }
        suspend fun drain() = withTimeout(10_000) {
            do { Shadows.shadowOf(Looper.getMainLooper()).idle(); delay(10) }
            while (scope.coroutineContext[Job]!!.children.any { it.isActive })
        }
        suspend fun capture() {
            setWindow()
            service.javaClass.getDeclaredMethod("captureEmptyTreeWeChatScreenshot", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(service, false)
            drain()
        }
        CollectionConsent.setEnabled(service, true)
        try {
            capture(); assertEquals(1, pending.size)
            variant = 3
            capture()
            assertEquals("合成夹具验证有损编码吞掉了微小像素变化", assetHashes.first(), assetHashes.last())
            assertEquals("原始正文1像素变化不能用相同WebP误删", 2, pending.size)
            variant = 0
            offset = 20; height = 740
            capture(); assertEquals("键盘/草稿使正文平移不应重存", 2, pending.size)
            variant = 1
            capture(); assertEquals("新增短消息保留", 3, pending.size)
            variant = 0; offset = 40
            capture(); assertEquals("A到B再到A命中近期内容", 3, pending.size)
            val queue = get("fallbackQueue") as NotificationScreenshotFallbackQueue
            val request = NotificationScreenshotFallbackRequest("body-history", System.currentTimeMillis())
            queue.offer(request)
            service.javaClass.getDeclaredMethod("onStableFallbackRequest", NotificationScreenshotFallbackRequest::class.java)
                .apply { isAccessible = true }.invoke(service, request)
            drain(); assertNull(queue.peek())
            assertEquals("通知补拍的同帧确认标题可查询已保存内容", 3, pending.size)
            variant = 2
            capture(); assertEquals("连续发送相同消息增加次数仍保存", 4, pending.size)
            identity = identity.copy(externalKey = "screenshot-v2:chat-b", exactTitleHash = "b".repeat(64))
            offset = 30
            capture(); assertEquals("不同会话不能共用内容历史", 5, pending.size)
        } finally {
            CollectionConsent.setEnabled(service, false); service.onDestroy(); ServiceShadow.root = null
            files.forEach { File(it).delete() }
        }
    }

    private fun sample(height: Int, offset: Int, variant: Int) = Bitmap.createBitmap(360, height, Bitmap.Config.ARGB_8888).apply {
        val canvas = Canvas(this)
        canvas.drawColor(Color.rgb(238, 238, 238))
        val paint = Paint()
        listOf(Color.GREEN, Color.WHITE, Color.BLUE).forEachIndexed { index, color ->
            paint.color = color
            canvas.drawRect(50f, 150f + index * 100 + offset, 220f, 180f + index * 100 + offset, paint)
        }
        if (variant in 1..2) {
            paint.color = Color.BLACK
            canvas.drawRect(50f, 450f + offset, 55f, 455f + offset, paint)
        }
        if (variant == 2) {
            paint.color = Color.BLACK
            canvas.drawRect(50f, 480f + offset, 55f, 485f + offset, paint)
        }
        if (variant == 3) setPixel(100, 260 + offset, Color.rgb(254, 254, 254))
        val top = height - 54f
        paint.color = Color.rgb(247, 247, 247)
        canvas.drawRect(0f, top, 360f, height.toFloat(), paint)
        paint.color = Color.BLACK; paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f
        canvas.drawCircle(23f, top + 26, 11f, paint)
        canvas.drawCircle(276f, top + 26, 11f, paint)
        paint.style = Paint.Style.FILL; paint.color = Color.rgb(7, 193, 96)
        canvas.drawRoundRect(296f, top + 10, 352f, top + 42, 4f, 4f, paint)
        paint.color = Color.WHITE
        canvas.drawRect(312f, top + 20, 315f, top + 32, paint)
        canvas.drawRect(327f, top + 20, 330f, top + 32, paint)
    }
}
