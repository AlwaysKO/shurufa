package com.yuyan.imemodule.data.capture.page

import android.graphics.Bitmap
import com.yuyan.imemodule.data.capture.media.awaitTitleOcrCompletion
import com.yuyan.imemodule.data.capture.media.ScreenshotSource
import com.yuyan.imemodule.data.capture.media.WindowScreenshotResult
import com.yuyan.imemodule.data.capture.ui.IntRect
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

internal data class PageFrame(val page: PageDecision, val bytes: ByteArray, val width: Int, val height: Int)

/**
 * 仅返回通过安全分类的窗口图片，不落盘、不联网；旧窗口/未知/敏感页直接回收。
 * 调用者必须使用导航代次校验 current，并将同意、输入/游戏守卫合入 allowed。
 * 空树探测仅在已授权宿主、事件触发与限频门禁后进行；候选帧只在内存用于本地识别。
 */
internal class PageFrameProbe(
    private val source: ScreenshotSource,
    private val recognize: suspend (Bitmap) -> List<PageLabel>,
    private val allowed: () -> Boolean,
) {
    suspend fun capture(
        packageName: String,
        windowId: Int,
        bounds: IntRect,
        treeLabels: List<PageLabel>,
        secureWindow: Boolean = false,
        chatVerified: Boolean = false,
        current: () -> Boolean,
        onAccepted: ((PageFrame) -> Unit)? = null,
    ): PageFrame? = withContext(Dispatchers.Default) {
        if (currentCoroutineContext()[com.yuyan.imemodule.data.capture.media.ChatCaptureAttempt] != null) return@withContext null
        if (!allowed() || !current()) return@withContext null
        val preflight = PageCapturePolicy.classify(packageName, bounds, treeLabels, secureWindow, chatVerified)
        if (preflight.kind == PageKind.CHAT || preflight.reason in setOf(
                "unsupported_package", "secure_window", "sensitive_input", "editable_non_chat", "invalid_bounds")) return@withContext null
        // 系统请求不能撤回，取消后仍须等回调结束才能释放调用方持有的物理截图槽。
        val image = withContext(NonCancellable) { source.capture(windowId, bounds) }
            as? WindowScreenshotResult.Success ?: return@withContext null
        var crop: Bitmap? = null
        try {
            currentCoroutineContext().ensureActive()
            if (!allowed() || !current()) return@withContext null
            val left = bounds.left - image.originX
            val top = bounds.top - image.originY
            val width = bounds.right - bounds.left
            val height = bounds.bottom - bounds.top
            if (left < 0 || top < 0 || width <= 0 || height <= 0 || left + width > image.bitmap.width || top + height > image.bitmap.height) return@withContext null
            val window = Bitmap.createBitmap(image.bitmap, left, top, width, height)
            crop = window
            val labels = awaitTitleOcrCompletion { recognize(window) }
            if (!allowed() || !current()) return@withContext null
            // 树仅用于预先拒绝风险；正向页面证据只能来自这一帧，不能与旧树拼导航。
            // OCR无结果时不只凭图标放行；敏感提示仍由同帧文本在分类最前拒绝。
            val miniApp = packageName == "com.tencent.mm" && labels.isNotEmpty() && hasWechatMiniAppCapsule(window)
            val page = PageCapturePolicy.classify(packageName, IntRect(0, 0, width, height), labels,
                miniAppChromeVerified = miniApp)
            if (page.kind == null || page.kind == PageKind.CHAT) return@withContext null
            val output = ByteArrayOutputStream()
            @Suppress("DEPRECATION")
            if (!window.compress(Bitmap.CompressFormat.WEBP, 85, output) || output.size() > 3 * 1024 * 1024) return@withContext null
            if (!allowed() || !current()) return@withContext null
            PageFrame(page, output.toByteArray(), width, height).also {
                // 在dispatcher返回前交出已接受帧；回调只交接引用，不能在物理截图锁内做磁盘I/O。
                onAccepted?.invoke(it)
            }
        } finally {
            if (crop !== image.bitmap) crop?.recycle()
            image.bitmap.recycle()
        }
    }
}
