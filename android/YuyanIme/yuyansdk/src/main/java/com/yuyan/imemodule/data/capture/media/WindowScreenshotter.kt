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
import kotlinx.coroutines.currentCoroutineContext
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
    private val captureAllowed: () -> Boolean = ImageUploadRuntime::isBackgroundWorkAllowed,
    private val supportedPackage: (String) -> Boolean = { AdapterRegistry.forPackage(it) != null },
) : ScreenshotSource {
    override suspend fun capture(windowId: Int, windowBounds: IntRect): WindowScreenshotResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return WindowScreenshotResult.Unsupported
        val attempt = currentCoroutineContext()[ChatCaptureAttempt]
        fun requestAllowed(): Boolean = attempt?.canTakeFrame() ?: captureAllowed()

        val result = suspendCancellableCoroutine<WindowScreenshotResult> { continuation ->
            val windowScoped = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
            fun fail(code: Int = AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR) {
                if (continuation.isActive) continuation.resume(WindowScreenshotResult.Failed(code))
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
                        fail(SCREENSHOT_WINDOW_UNCONFIRMED)
                        return
                    }
                    if (!requestAllowed()) {
                        hardwareBuffer.close()
                        fail(SCREENSHOT_BACKGROUND_PAUSED)
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
                if (!requestAllowed()) { fail(SCREENSHOT_BACKGROUND_PAUSED); return@execute }
                // 获取前只读当前窗口的包名/ID，避免排队后已经离开聊天仍截取其他 App。
                if (currentChatWindowId() != windowId) { fail(SCREENSHOT_WINDOW_UNCONFIRMED); return@execute }
                if (!continuation.isActive) return@execute
                if (!requestAllowed()) { fail(SCREENSHOT_BACKGROUND_PAUSED); return@execute }
                // 不能提前到媒体层锁定：IO排队、窗口Binder检查期间仍可能变化。
                if (attempt?.beginFrameRequest() == false) { fail(SCREENSHOT_BACKGROUND_PAUSED); return@execute }
                try {
                    if (windowScoped) service.takeScreenshotOfWindow(windowId, executor, callback)
                    else service.takeScreenshot(Display.DEFAULT_DISPLAY, executor, callback)
                } catch (_: SecurityException) { fail(AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS) }
                catch (_: Exception) { fail(SCREENSHOT_REQUEST_EXCEPTION) }
            }
        }
        if (result !is WindowScreenshotResult.Success) attempt?.frameRequestFailed()
        return result
    }

    @Suppress("DEPRECATION")
    private fun currentChatWindowId(): Int? = runCatching {
        val root = service.rootInActiveWindow
        if (root != null) {
            try { if (supportedPackage(root.packageName?.toString().orEmpty())) root.windowId else null }
            finally { root.recycle() }
        } else {
            // activeRoot 暂空不代表已离开，但仅窗口root的确切包名可补证，不能用事件包名猜测。
            val window = service.windows.filter { it.isActive && it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }.singleOrNull()
                ?: return@runCatching null
            val windowRoot = window.root ?: return@runCatching null
            try { if (supportedPackage(windowRoot.packageName?.toString().orEmpty())) window.id else null }
            finally { windowRoot.recycle() }
        }
    }.getOrNull()
}

// API 30–33 是整屏截图，回调时必须仍在原窗口；API 34+ 已绑定原窗口，可保留离开前那一帧。
internal fun canUseScreenshotResult(windowScoped: Boolean, requestedWindowId: Int, activeWindowId: Int?): Boolean =
    windowScoped || activeWindowId == requestedWindowId

// 负值是本地安全预检枚举，正值原样保留 Android 的真实截图错误码。
internal const val SCREENSHOT_WINDOW_UNCONFIRMED = -1001
internal const val SCREENSHOT_BACKGROUND_PAUSED = -1002
internal const val SCREENSHOT_REQUEST_EXCEPTION = -1003
internal const val SCREENSHOT_ASSET_PREPARATION_FAILED = -1004
internal fun isTransientScreenshotError(code: Int): Boolean = code == 1 || code == 3
