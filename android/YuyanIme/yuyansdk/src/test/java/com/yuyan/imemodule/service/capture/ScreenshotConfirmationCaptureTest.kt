package com.yuyan.imemodule.service.capture

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.yuyan.imemodule.data.capture.*
import com.yuyan.imemodule.data.capture.db.*
import com.yuyan.imemodule.data.capture.media.*
import com.yuyan.imemodule.data.collect.CollectionConsent
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "mdpi", shadows = [WechatListCaptureTest.ServiceShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotConfirmationCaptureTest {
    @Test fun firstImageAndConfirmationReplayShareCaptureIdButDifferentFramesDoNot() = runBlocking {
        val service = Robolectric.buildService(PassiveChatAccessibilityService::class.java).create().get()
        fun set(name: String, value: Any) = service.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
        val scope = service.javaClass.getDeclaredField("backgroundScope").apply { isAccessible = true }.get(service) as CoroutineScope
        val pending = mutableListOf<PendingMessageEntity>()
        val files = mutableSetOf<String>()
        var variant = 0
        val seen = mutableSetOf<String>()
        val store = object : CaptureOutboxStore {
            override suspend fun enqueueIfNew(seenMessage: SeenMessageEntity, pendingMessage: PendingMessageEntity,
                pendingAssets: List<PendingAssetEntity>): Boolean {
                if (!seen.add(seenMessage.fingerprint)) return false
                pending += pendingMessage
                return true
            }
        }
        set("coordinator", CaptureCoordinator(store = store, deviceId = { "device" }, wakeUploader = {}))
        set("mediaCapturer", WindowMediaCapturer(service, ScreenshotSource { _, _ ->
            WindowScreenshotResult.Success(Bitmap.createBitmap(400, 800, Bitmap.Config.ARGB_8888).apply {
                eraseColor(if (variant == 0) android.graphics.Color.WHITE else android.graphics.Color.LTGRAY)
            }, 0, 0)
        }))
        val tracker = WechatTitleStabilizer()
        var time = 0L
        set("screenshotIdentityResolver", object : ScreenshotConversationIdentityResolver {
            override suspend fun resolve(asset: PendingAssetEntity, expectedVersion: Long, titleInput: TitleOcrInput?): ScreenshotConversationIdentity {
                files += asset.localPath
                time += 1_000
                return tracker.observe("测试联系人", "a".repeat(64), time)
            }
        })
        val root = AccessibilityNodeInfo.obtain().apply {
            packageName = "com.tencent.mm"; className = "android.widget.FrameLayout"
            setBoundsInScreen(Rect(0, 0, 400, 800))
        }
        WechatListCaptureTest.ServiceShadow.root = root
        val window = AccessibilityWindowInfo.obtain()
        Shadows.shadowOf(window).apply {
            setRoot(root); setId(root.windowId); setActive(true)
            setType(AccessibilityWindowInfo.TYPE_APPLICATION); setBoundsInScreen(Rect(0, 0, 400, 800))
        }
        Shadows.shadowOf(service).setWindows(listOf(window))
        suspend fun capture() {
            service.javaClass.getDeclaredMethod("captureEmptyTreeWeChatScreenshot", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(service, false)
            val job = scope.coroutineContext[Job]!!.children.firstOrNull()
            withTimeout(10_000) {
                do { Shadows.shadowOf(Looper.getMainLooper()).idle(); delay(10) } while (job?.isActive == true)
            }
        }
        fun metadata(index: Int): JsonObject = Json.parseToJsonElement(pending[index].payloadJson).jsonObject
            .getValue("message").jsonObject.getValue("metadata").jsonObject
        CollectionConsent.setEnabled(service, true)
        try {
            capture()
            assertEquals(2, pending.size)
            val first = metadata(0).getValue("screenshot_capture_id").jsonPrimitive.content
            assertEquals(first, UUID.fromString(first).toString())
            assertEquals(first, metadata(1).getValue("screenshot_capture_id").jsonPrimitive.content)
            assertEquals("pending", metadata(0).getValue("conversation_identity_status").jsonPrimitive.content)
            assertEquals("confirmed", metadata(1).getValue("conversation_identity_status").jsonPrimitive.content)
            variant = 1; capture()
            assertEquals(3, pending.size)
            assertNotEquals(first, metadata(2).getValue("screenshot_capture_id").jsonPrimitive.content)
        } finally {
            CollectionConsent.setEnabled(service, false); service.onDestroy()
            WechatListCaptureTest.ServiceShadow.root = null
            files.forEach { File(it).delete() }
        }
    }
}
