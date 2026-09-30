package com.yuyan.imemodule.data.capture.media

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import com.yuyan.imemodule.data.collect.ImageUploadRuntime
import android.view.Display
import com.yuyan.imemodule.data.capture.ui.IntRect
import com.yuyan.imemodule.data.capture.adapter.AdapterRegistry
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlin.coroutines.resume

fun interface ScreenshotSource {
    suspend fun capture(windowId: Int, windowBounds: IntRect): WindowScreenshotResult
}

sealed interface WindowScreenshotResult {
    data class Success(
        val bitmap: Bitmap,
        val originX: Int,
        val originY: Int,
    ) : WindowScreenshotResult

    data object Unsupported : WindowScreenshotResult
    data class Failed(val errorCode: Int) : WindowScreenshotResult
}

class WindowScreenshotter(
    private val service: AccessibilityService,
) : ScreenshotSource {
    override suspend fun capture(windowId: Int, windowBounds: IntRect): WindowScreenshotResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return WindowScreenshotResult.Unsupported

        return suspendCancellableCoroutine { continuation ->
            val windowScoped = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
            fun fail() {
                if (continuation.isActive) continuation.resume(WindowScreenshotResult.Failed(AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR))
            }
            val callback = object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    val hardwareBuffer = screenshot.hardwareBuffer
                    if (!continuation.isActive) {
                        hardwareBuffer.close()
                        return
                    }
                    // 新版已绑定窗口，无须再跨进程读取当前页面；整屏截图仍需复核导航。
                    if (!windowScoped && !canUseScreenshotResult(false, windowId, currentChatWindowId())) {
                        hardwareBuffer.close()
                        fail()
                        return
                    }
                    if (!ImageUploadRuntime.isInputIdle()) {
                        hardwareBuffer.close()
                        fail()
                        return
                    }
                    val bitmap = try {
                        runCatching {
                            val wrapped = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                            try { wrapped?.copy(Bitmap.Config.ARGB_8888, false) }
                            finally { wrapped?.recycle() }
                        }.getOrNull()
                    } finally {
                        hardwareBuffer.close()
                    }
                    val result = if (bitmap == null) {
                        WindowScreenshotResult.Failed(AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR)
                    } else {
                        val isWindowScreenshot = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                        WindowScreenshotResult.Success(
                            bitmap = bitmap,
                            originX = if (isWindowScreenshot) windowBounds.left else 0,
                            originY = if (isWindowScreenshot) windowBounds.top else 0,
                        )
                    }
                    if (continuation.isActive) {
                        continuation.resume(result) { _, undelivered, _ ->
                            if (undelivered is WindowScreenshotResult.Success) undelivered.bitmap.recycle()
                        }
                    } else if (result is WindowScreenshotResult.Success) {
                        result.bitmap.recycle()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    if (continuation.isActive) continuation.resume(WindowScreenshotResult.Failed(errorCode))
                }
            }
            // 截图与键盘共用进程：Binder 等待和整帧像素复制均不能占用键盘主线程。
            val executor = Dispatchers.IO.asExecutor()
            executor.execute {
                if (!continuation.isActive) return@execute
                if (!ImageUploadRuntime.isInputIdle()) { fail(); return@execute }
                // 获取前只读当前窗口的包名/ID，避免排队后已经离开聊天仍截取其他 App。
                if (currentChatWindowId() != windowId) { fail(); return@execute }
                if (!continuation.isActive) return@execute
                if (!ImageUploadRuntime.isInputIdle()) { fail(); return@execute }
                try {
                    if (windowScoped) service.takeScreenshotOfWindow(windowId, executor, callback)
                    else service.takeScreenshot(Display.DEFAULT_DISPLAY, executor, callback)
                } catch (_: Exception) { fail() }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun currentChatWindowId(): Int? = runCatching {
        val root = service.rootInActiveWindow ?: return@runCatching null
        try { if (AdapterRegistry.forPackage(root.packageName?.toString().orEmpty()) != null) root.windowId else null }
        finally { root.recycle() }
    }.getOrNull()
}

// API 30–33 是整屏截图，回调时必须仍在原窗口；API 34+ 已绑定原窗口，可保留离开前那一帧。
internal fun canUseScreenshotResult(windowScoped: Boolean, requestedWindowId: Int, activeWindowId: Int?): Boolean =
    windowScoped || activeWindowId == requestedWindowId
