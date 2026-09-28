package com.yuyan.imemodule.data.capture.media

import android.accessibilityservice.AccessibilityService
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import com.yuyan.imemodule.data.capture.ui.IntRect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAccessibilityService
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], shadows = [WindowScreenshotterThreadTest.ServiceShadow::class])
class WindowScreenshotterThreadTest {
    class TestService : AccessibilityService() {
        override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) = Unit
        override fun onInterrupt() = Unit
    }

    @Implements(AccessibilityService::class)
    class ServiceShadow : ShadowAccessibilityService() {
        @Implementation
        fun getRootInActiveWindow(): AccessibilityNodeInfo {
            operations += "root" to Thread.currentThread()
            return AccessibilityNodeInfo.obtain().apply { packageName = "com.tencent.mm" }
        }

        @Implementation
        override fun takeScreenshot(displayId: Int, executor: Executor, callback: AccessibilityService.TakeScreenshotCallback) {
            operations += "request" to Thread.currentThread()
            delayedCallback?.let { it.complete(executor to callback); return }
            executor.execute {
                operations += "callback" to Thread.currentThread()
                callback.onFailure(AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR)
            }
        }

        companion object {
            val operations = CopyOnWriteArrayList<Pair<String, Thread>>()
            var delayedCallback: CompletableDeferred<Pair<Executor, AccessibilityService.TakeScreenshotCallback>>? = null
        }
    }

    @Test
    fun screenshotRequestWindowReadAndPixelCallbackDoNotOccupyKeyboardMainThread() = runBlocking {
        ServiceShadow.operations.clear()
        val service = Robolectric.buildService(TestService::class.java).create().get()
        try {
            val result = async(Dispatchers.Default) {
                WindowScreenshotter(service).capture(-1, IntRect(0, 0, 1080, 1920))
            }
            withTimeout(5_000) {
                while (!result.isCompleted) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    delay(10)
                }
            }
            assertEquals(WindowScreenshotResult.Failed(AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR), result.await())
            assertEquals(listOf("root", "request", "callback"), ServiceShadow.operations.map { it.first })
            for ((operation, thread) in ServiceShadow.operations) {
                assertFalse("截图 $operation 不能阻塞键盘主线程", thread === Looper.getMainLooper().thread)
            }
        } finally {
            service.onDestroy()
            ServiceShadow.operations.clear()
        }
    }

    @Test
    fun cancelledCaptureClosesLateBufferWithoutReadingWindowAgain() = runBlocking {
        ServiceShadow.operations.clear()
        val callbackReady = CompletableDeferred<Pair<Executor, AccessibilityService.TakeScreenshotCallback>>()
        ServiceShadow.delayedCallback = callbackReady
        val service = Robolectric.buildService(TestService::class.java).create().get()
        val buffer = HardwareBuffer.create(4, 4, HardwareBuffer.RGBA_8888, 1, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
        try {
            val capture = async(Dispatchers.Default) {
                WindowScreenshotter(service).capture(-1, IntRect(0, 0, 1080, 1920))
            }
            withTimeout(5_000) {
                while (!callbackReady.isCompleted) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    delay(10)
                }
            }
            capture.cancel()
            val screenshot = ReflectionHelpers.callConstructor(AccessibilityService.ScreenshotResult::class.java,
                ClassParameter.from(HardwareBuffer::class.java, buffer),
                ClassParameter.from(ColorSpace::class.java, ColorSpace.get(ColorSpace.Named.SRGB)),
                ClassParameter.from(Long::class.javaPrimitiveType, 0L))
            val (executor, callback) = callbackReady.await()
            val delivered = CompletableDeferred<Unit>()
            executor.execute {
                try { callback.onSuccess(screenshot) }
                finally { delivered.complete(Unit) }
            }
            withTimeout(5_000) {
                while (!delivered.isCompleted) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    delay(10)
                }
            }
            assertTrue("取消后到达的硬件图像必须关闭", buffer.isClosed)
            assertEquals("取消后无需继续读取页面或复制整帧", 1, ServiceShadow.operations.count { it.first == "root" })
        } finally {
            buffer.close()
            service.onDestroy()
            ServiceShadow.delayedCallback = null
            ServiceShadow.operations.clear()
        }
    }
}
