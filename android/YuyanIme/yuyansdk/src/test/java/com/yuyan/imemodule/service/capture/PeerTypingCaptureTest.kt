package com.yuyan.imemodule.service.capture

import android.accessibilityservice.AccessibilityService
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAccessibilityService
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
@Config(sdk = [30], shadows = [PeerTypingCaptureTest.ServiceShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PeerTypingCaptureTest {
    @Implements(AccessibilityService::class)
    class ServiceShadow : ShadowAccessibilityService() {
        @Implementation fun getRootInActiveWindow(): AccessibilityNodeInfo? = root?.let { AccessibilityNodeInfo.obtain(it) }
        companion object { var root: AccessibilityNodeInfo? = null }
    }
    @Test fun notificationTypingAndNonChatTreesAreBlockedWhileEmptyTreeUsesOnlyTheExactHeader() = runBlocking {
        val service = Robolectric.buildService(PassiveChatAccessibilityService::class.java).create().get()
        fun set(name: String, value: Any) = service.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
        fun get(name: String): Any = requireNotNull(service.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(service))
        val scope = get("backgroundScope") as CoroutineScope
        val queue = get("fallbackQueue") as NotificationScreenshotFallbackQueue
        var captures = 0
        var enqueued = 0
        var resolvedIdentity = unresolvedWechatScreenshotIdentity("对方正在输入..8")
        var hasInputBar = false
        val persistedPayloads = mutableListOf<String>()
        val headers = mutableListOf<Pair<Boolean, Int?>>()
        val files = mutableSetOf<String>()
        fun node(id: String?, text: String?, bounds: Rect, clazz: String) = AccessibilityNodeInfo.obtain().apply {
            packageName = "com.tencent.mm"; viewIdResourceName = id; this.text = text; className = clazz
            isVisibleToUser = true
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
            val previousJobs = scope.coroutineContext[Job]!!.children.toSet()
            service.javaClass.getDeclaredMethod("onStableFallbackRequest", NotificationScreenshotFallbackRequest::class.java)
                .apply { isAccessible = true }.invoke(service, request)
            val requestJob = scope.coroutineContext[Job]!!.children.firstOrNull { it !in previousJobs }
            withTimeout(5_000) {
                do {
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    delay(10)
                } while (requestJob?.isActive == true)
            }
            queue.removeIfSame(request)
        }
        CollectionConsent.setEnabled(service, true)
        set("coordinator", CaptureCoordinator(store = object : CaptureOutboxStore {
            override suspend fun enqueueIfNew(seenMessage: SeenMessageEntity, pendingMessage: PendingMessageEntity,
                pendingAssets: List<PendingAssetEntity>): Boolean {
                enqueued++
                persistedPayloads += pendingMessage.payloadJson
                return true
            }
        }, deviceId = { "test-device" }, wakeUploader = {}))
        set("mediaCapturer", WindowMediaCapturer(service, ScreenshotSource { _, _ ->
            captures++
            val bitmap = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
            if (hasInputBar) {
                val density = service.resources.displayMetrics.density
                val canvas = android.graphics.Canvas(bitmap)
                canvas.drawColor(android.graphics.Color.rgb(80, 100, 120))
                val paint = android.graphics.Paint().apply { color = android.graphics.Color.rgb(247, 247, 247) }
                val top = bitmap.height - 54 * density
                canvas.drawRect(0f, top, bitmap.width.toFloat(), bitmap.height.toFloat(), paint)
                paint.color = android.graphics.Color.BLACK
                paint.style = android.graphics.Paint.Style.STROKE
                paint.strokeWidth = 2 * density
                val cy = top + 26 * density
                val right = bitmap.width - 22 * density
                canvas.drawCircle(23 * density, cy, 11 * density, paint)
                canvas.drawCircle(right, cy, 11 * density, paint)
                canvas.drawLine(right - 5 * density, cy, right + 5 * density, cy, paint)
                canvas.drawLine(right, cy - 5 * density, right, cy + 5 * density, paint)
            }
            WindowScreenshotResult.Success(bitmap, 0, 0)
        }))
        set("screenshotIdentityResolver", object : ScreenshotConversationIdentityResolver {
            override suspend fun resolve(asset: PendingAssetEntity, expectedVersion: Long, titleInput: TitleOcrInput?): ScreenshotConversationIdentity {
                files += asset.localPath
                val header = titleInput?.takeOrDecode(asset.localPath)
                headers += (titleInput?.hasExactTitleBand == true) to header?.height
                header?.recycle()
                return resolvedIdentity
            }
        })
        try {
            for (title in listOf("对方正在輸入...", "對方正在輸入…", "对方 正在輸入..8", "对方正在输入", "对方正在输入.", "对方正在输入..8")) {
                ServiceShadow.root = root(title)
                runRequest(NotificationScreenshotFallbackRequest(title, System.currentTimeMillis()))
            }
            assertEquals("页面树明确为输入状态时不能退回截图", 0, captures)
            val editor = root(null).apply {
                Shadows.shadowOf(this).addChild(node("com.tencent.mm:id/title", "编辑标签", Rect(340, 50, 720, 130), "android.widget.TextView"))
                Shadows.shadowOf(this).addChild(node(null, "完成", Rect(910, 40, 1040, 135), "android.widget.Button"))
                Shadows.shadowOf(this).addChild(node(null, "亲情", Rect(40, 450, 540, 560), "android.widget.EditText"))
            }
            val webForm = root(null).apply {
                Shadows.shadowOf(this).addChild(node("com.tencent.mm:id/title", "登录验证", Rect(340, 50, 720, 130), "android.widget.TextView"))
                Shadows.shadowOf(this).addChild(node(null, null, Rect(0, 150, 1080, 1920), "android.webkit.WebView").apply {
                    Shadows.shadowOf(this).addChild(node(null, null, Rect(100, 1100, 900, 1220), "android.widget.EditText"))
                })
            }
            for ((index, nonChatRoot) in listOf(editor, webForm).withIndex()) {
                ServiceShadow.root = nonChatRoot
                runRequest(NotificationScreenshotFallbackRequest("non-chat-$index", System.currentTimeMillis()))
            }
            assertEquals("已识别的编辑/网页不能通过通知入口退回空树截图", 0, captures)
            assertEquals(0, enqueued)
            assertTrue(headers.isEmpty())
            ServiceShadow.root = root(null)
            runRequest(NotificationScreenshotFallbackRequest("empty-tree", System.currentTimeMillis()))
            assertEquals(1, captures)
            assertEquals(listOf(true to wechatTitleBand(0, 0, service.resources.displayMetrics.density).height), headers)
            assertEquals("输入状态截图不能进入上报队列", 0, enqueued)
            resolvedIdentity = unresolvedWechatScreenshotIdentity("付款").copy(isChatPage = false)
            ServiceShadow.root = root(null)
            runRequest(NotificationScreenshotFallbackRequest("non-chat-empty-tree", System.currentTimeMillis()))
            assertEquals("空树页面仍需截图识别页面结构", 2, captures)
            assertEquals("OCR已拒绝的非聊天页面不能通过通知入口上报", 0, enqueued)
            // 被丢弃的状态帧和非聊天页不能更新已上报图片缓存。
            assertFalse(service.getSharedPreferences("notification_screenshot_fallback", 0).contains("last_screenshot_sha256"))
            resolvedIdentity = unresolvedWechatScreenshotIdentity("普通标题").copy(isChatPage = true)
            runRequest(NotificationScreenshotFallbackRequest("title-without-chat-controls", System.currentTimeMillis()))
            assertEquals("仅有标题且无聊天输入栏不能作为明确聊天截图", 0, enqueued)
            hasInputBar = true
            runRequest(NotificationScreenshotFallbackRequest("verified-chat-controls", System.currentTimeMillis()))
            assertEquals("输入栏与标题证据俱全时不要求复杂背景支持内容去重", 1, enqueued)
            val metadata = kotlinx.serialization.json.Json.parseToJsonElement(persistedPayloads.single()).let {
                (it as kotlinx.serialization.json.JsonObject)["message"] as kotlinx.serialization.json.JsonObject
            }["metadata"] as kotlinx.serialization.json.JsonObject
            assertEquals(kotlinx.serialization.json.JsonPrimitive("true"), metadata["screenshot_chat_verified"])
        } finally {
            CollectionConsent.setEnabled(service, false)
            service.onDestroy()
            ServiceShadow.root = null
            files.forEach { File(it).delete() }
        }
    }
}
