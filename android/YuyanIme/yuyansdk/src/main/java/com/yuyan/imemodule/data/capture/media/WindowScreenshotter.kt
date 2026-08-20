package com.yuyan.imemodule.data.capture.media

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import androidx.core.content.ContextCompat
import com.yuyan.imemodule.data.capture.ui.IntRect
import kotlinx.coroutines.suspendCancellableCoroutine
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
            val callback = object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    val hardwareBuffer = screenshot.hardwareBuffer
                    val bitmap = try {
                        runCatching {
                            Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                                ?.copy(Bitmap.Config.ARGB_8888, false)
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
                        continuation.resume(result)
                    } else if (result is WindowScreenshotResult.Success) {
                        result.bitmap.recycle()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    if (continuation.isActive) continuation.resume(WindowScreenshotResult.Failed(errorCode))
                }
            }
            val executor = ContextCompat.getMainExecutor(service)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    service.takeScreenshotOfWindow(windowId, executor, callback)
                } else {
                    service.takeScreenshot(Display.DEFAULT_DISPLAY, executor, callback)
                }
            } catch (_: Exception) {
                if (continuation.isActive) {
                    continuation.resume(
                        WindowScreenshotResult.Failed(
                            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR,
                        ),
                    )
                }
            }
        }
    }
}
