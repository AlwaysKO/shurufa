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
        fun guarded(): Boolean {
            if (!current()) { PageProbeDiagnostics.report(PageProbeStatus.SCOPE_LOST); return false }
            if (!allowed()) { PageProbeDiagnostics.report(PageProbeStatus.GUARD_REJECTED); return false }
            return true
        }
        if (currentCoroutineContext()[com.yuyan.imemodule.data.capture.media.ChatCaptureAttempt] != null) {
            PageProbeDiagnostics.report(PageProbeStatus.CHAT_ATTEMPT_REJECTED)
            return@withContext null
        }
        if (!guarded()) return@withContext null
        val preflight = PageCapturePolicy.classify(packageName, bounds, treeLabels, secureWindow, chatVerified)
        if (preflight.kind == PageKind.CHAT || preflight.reason in setOf(
                "unsupported_package", "secure_window", "sensitive_input", "editable_non_chat", "invalid_bounds")) {
            PageProbeDiagnostics.report(PageProbeStatus.PREFLIGHT_REJECTED, preflight.reason)
            return@withContext null
        }
        // 系统请求不能撤回，取消后仍须等回调结束才能释放调用方持有的物理截图槽。
        val result = withContext(NonCancellable) { source.capture(windowId, bounds) }
        val image = when (result) {
            is WindowScreenshotResult.Success -> result
            is WindowScreenshotResult.Failed -> {
                PageProbeDiagnostics.report(PageProbeStatus.SYSTEM_FAILED, errorCode = result.errorCode)
                return@withContext null
            }
            WindowScreenshotResult.Unsupported -> {
                PageProbeDiagnostics.report(PageProbeStatus.SYSTEM_UNSUPPORTED)
                return@withContext null
            }
        }
        var crop: Bitmap? = null
        try {
            currentCoroutineContext().ensureActive()
            if (!guarded()) return@withContext null
            val left = bounds.left - image.originX
            val top = bounds.top - image.originY
            val width = bounds.right - bounds.left
            val height = bounds.bottom - bounds.top
            if (left < 0 || top < 0 || width <= 0 || height <= 0 || left + width > image.bitmap.width || top + height > image.bitmap.height) {
                PageProbeDiagnostics.report(PageProbeStatus.INVALID_CROP)
                return@withContext null
            }
            val window = Bitmap.createBitmap(image.bitmap, left, top, width, height)
            crop = window
            val labels = awaitTitleOcrCompletion { recognize(window) }
            if (labels.isEmpty()) PageProbeDiagnostics.report(PageProbeStatus.OCR_EMPTY)
            if (!guarded()) return@withContext null
            // 树仅用于预先拒绝风险；正向页面证据只能来自这一帧，不能与旧树拼导航。
            // OCR无结果时不只凭图标放行；敏感提示仍由同帧文本在分类最前拒绝。
            val miniApp = packageName == "com.tencent.mm" && labels.isNotEmpty() && hasWechatMiniAppCapsule(window)
            val page = PageCapturePolicy.classify(packageName, IntRect(0, 0, width, height), labels,
                miniAppChromeVerified = miniApp)
            if (page.kind == null || page.kind == PageKind.CHAT) {
                PageProbeDiagnostics.report(PageProbeStatus.CLASSIFICATION_REJECTED, page.reason)
                if (page.reason == "insufficient_evidence") PageProbeDiagnostics.feedNavigation(
                    PageCapturePolicy.feedNavigationEvidence(IntRect(0, 0, width, height), labels))
                return@withContext null
            }
            val output = ByteArrayOutputStream()
            @Suppress("DEPRECATION")
            if (!window.compress(Bitmap.CompressFormat.WEBP, 85, output)) {
                PageProbeDiagnostics.report(PageProbeStatus.ENCODING_FAILED)
                return@withContext null
            }
            if (output.size() > 3 * 1024 * 1024) {
                PageProbeDiagnostics.report(PageProbeStatus.IMAGE_TOO_LARGE)
                return@withContext null
            }
            if (!guarded()) return@withContext null
            PageFrame(page, output.toByteArray(), width, height).also {
                // 在dispatcher返回前交出已接受帧；回调只交接引用，不能在物理截图锁内做磁盘I/O。
                onAccepted?.invoke(it)
                PageProbeDiagnostics.report(PageProbeStatus.FRAME_ACCEPTED)
            }
        } finally {
            if (crop !== image.bitmap) crop?.recycle()
            image.bitmap.recycle()
        }
    }
}
