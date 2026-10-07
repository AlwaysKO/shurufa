package com.yuyan.imemodule.data.capture.media

import com.yuyan.imemodule.data.capture.CaptureLayer
import com.yuyan.imemodule.data.capture.CaptureTrace
import com.yuyan.imemodule.data.capture.CaptureStage
import android.content.Context
import com.yuyan.imemodule.data.collect.ImageUploadRuntime
import android.graphics.Bitmap
import com.yuyan.imemodule.data.capture.db.PendingAssetEntity
import com.yuyan.imemodule.data.capture.sha256
import com.yuyan.imemodule.data.capture.ui.IntRect
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class MediaCaptureRequest(
    val messageIndex: Int,
    val bounds: IntRect,
    val inputAreaBounds: IntRect? = null,
    val lossyWebp: Boolean = false,
    val titleOcrInput: TitleOcrInput? = null,
    val wechatInputBarDensity: Float? = null,
    val contentInput: ScreenshotContentInput? = null,
    val platform: com.yuyan.imemodule.data.capture.model.ChatPlatform? = null,
)

class MediaCropper(private val minimumSide: Int = 16) {
    fun crop(
        bitmap: Bitmap,
        requested: IntRect,
        windowBounds: IntRect,
        screenshotOriginX: Int,
        screenshotOriginY: Int,
        inputAreaBounds: IntRect? = null,
    ): Bitmap? {
        if (requested.width <= 0 || requested.height <= 0) return null
        if (inputAreaBounds != null && requested.intersection(inputAreaBounds) != null) return null

        val screenshotBounds = IntRect(
            screenshotOriginX,
            screenshotOriginY,
            screenshotOriginX + bitmap.width,
            screenshotOriginY + bitmap.height,
        )
        val clamped = requested.intersection(windowBounds)?.intersection(screenshotBounds) ?: return null
        if (clamped.width < minimumSide || clamped.height < minimumSide) return null

        val result = Bitmap.createBitmap(
            bitmap,
            clamped.left - screenshotOriginX,
            clamped.top - screenshotOriginY,
            clamped.width,
            clamped.height,
        )
        return if (result === bitmap) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            result
        }
    }
}

fun interface MediaAssetCapturer {
    suspend fun capture(
        windowId: Int,
        windowBounds: IntRect,
        requests: List<MediaCaptureRequest>,
    ): Map<Int, PendingAssetEntity>
}

class WindowMediaCapturer(
    private val context: Context,
    private val screenshotSource: ScreenshotSource,
    private val cropper: MediaCropper = MediaCropper(),
    private val processingDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val captureAllowed: () -> Boolean = { true },
    private val captureGeneration: () -> Long = { 0L },
    private val onScreenshotResult: (String, String, Int?) -> Unit = { _, _, _ -> },
    private val encodeAsset: (Bitmap, Boolean) -> ByteArray = { bitmap, lossy -> if (lossy) encodeWebp(bitmap) else encodeLossless(bitmap) },
) : MediaAssetCapturer {
    // 三 App 的主视口、空树和通知补偿共享本实例，不各自向系统并发截图。
    private val captureMutex = Mutex()

    override suspend fun capture(
        windowId: Int,
        windowBounds: IntRect,
        requests: List<MediaCaptureRequest>,
    ): Map<Int, PendingAssetEntity> {
        val requestedGeneration = captureGeneration()
        val attempt = currentCoroutineContext()[ChatCaptureAttempt]
        fun requestAllowed(): Boolean = attempt?.canTakeFrame() ?: (
            ImageUploadRuntime.isBackgroundWorkAllowed() && captureAllowed() && captureGeneration() == requestedGeneration)
        fun processingAuthorized(): Boolean = attempt?.let { it.isAccepted && it.isAuthorized() } ?: (
            captureAllowed() && captureGeneration() == requestedGeneration)
        val platform = requests.firstNotNullOfOrNull { it.platform }?.wireName
        fun report(status: String, code: Int? = null) { platform?.let { runCatching { onScreenshotResult(it, status, code) } } }
        suspend fun captureLocked(): Map<Int, PendingAssetEntity> {
            currentCoroutineContext().ensureActive()
            if (requests.isEmpty() || !requestAllowed()) {
                CaptureTrace.record(CaptureStage.REQUEST_CANCELLED, windowId, requestedGeneration, layer = CaptureLayer.MEDIA)
                report("cancelled", SCREENSHOT_BACKGROUND_PAUSED)
                return emptyMap()
            }
            CaptureTrace.record(CaptureStage.SYSTEM_REQUEST, windowId, requestedGeneration, requests.size, layer = CaptureLayer.MEDIA)
            // 系统截图提交后不能撤销。取消也必须等回调收尾，才能放行下一次物理请求。
            var screenshot = withContext(NonCancellable) { screenshotSource.capture(windowId, windowBounds) }
            var retries = 0
            // 发送快帧由服务层在本次渲染窗口内补试，不叠加400ms的普通截图重试。
            while (attempt?.allowsSettledSendFrame != true && screenshot is WindowScreenshotResult.Failed &&
                isTransientScreenshotError(screenshot.errorCode) && retries < 2) {
                currentCoroutineContext().ensureActive()
                if (!requestAllowed()) break
                kotlinx.coroutines.delay(400)
                if (!requestAllowed()) break
                retries++
                screenshot = withContext(NonCancellable) { screenshotSource.capture(windowId, windowBounds) }
            }
            try {
                currentCoroutineContext().ensureActive()
            } catch (cancelled: CancellationException) {
                if (screenshot is WindowScreenshotResult.Success) screenshot.bitmap.recycle()
                report("cancelled")
                throw cancelled
            }
            CaptureTrace.record(CaptureStage.SYSTEM_READY, windowId, requestedGeneration, value = (screenshot as? WindowScreenshotResult.Failed)?.errorCode ?: -1, flag = screenshot is WindowScreenshotResult.Success, layer = CaptureLayer.MEDIA)
            if (screenshot !is WindowScreenshotResult.Success) {
                report(if (!requestAllowed()) "cancelled" else "failed", (screenshot as? WindowScreenshotResult.Failed)?.errorCode)
                return emptyMap()
            }

            val successfulScreenshot = screenshot
            try {
                if (attempt != null && (!attempt.acceptFrame() ||
                        !ImageUploadRuntime.awaitBackgroundWorkAllowed(attempt::isAuthorized))) {
                    report("cancelled")
                    return emptyMap()
                }
                if (!ImageUploadRuntime.isBackgroundWorkAllowed() || !processingAuthorized()) {
                    report("cancelled")
                    return emptyMap()
                }
                val assets = withContext(processingDispatcher) {
                    buildMap {
                        requests.forEach { request ->
                            if (!ImageUploadRuntime.isBackgroundWorkAllowed() || !processingAuthorized()) return@forEach
                            val originalCrop = cropper.crop(
                                bitmap = successfulScreenshot.bitmap,
                                requested = request.bounds,
                                windowBounds = windowBounds,
                                screenshotOriginX = successfulScreenshot.originX,
                                screenshotOriginY = successfulScreenshot.originY,
                                inputAreaBounds = request.inputAreaBounds,
                            ) ?: return@forEach
                            var cropped = originalCrop
                            try {
                                request.titleOcrInput?.captureFrom(originalCrop)
                                var bodyBoundaryVerified = request.wechatInputBarDensity == null
                                request.wechatInputBarDensity?.let { density ->
                                    wechatInputBarTop(originalCrop, density)?.let { top ->
                                        bodyBoundaryVerified = true
                                        cropped = Bitmap.createBitmap(originalCrop, 0, 0, originalCrop.width, top)
                                    }
                                }
                                request.contentInput?.captureFrom(cropped, context.resources.displayMetrics.density, bodyBoundaryVerified)
                                if (!ImageUploadRuntime.isBackgroundWorkAllowed() || !processingAuthorized()) return@forEach
                                val encoded = encodeAsset(cropped, request.lossyWebp)
                                val contentHash = sha256(encoded)
                                val output = File(context.cacheDir, "chat-capture/$contentHash")
                                if (!output.isFile) {
                                    output.parentFile?.mkdirs()
                                    val temporary = File(output.parentFile, "$contentHash.tmp")
                                    temporary.writeBytes(encoded)
                                    if (!temporary.renameTo(output) && !output.isFile) {
                                        temporary.delete()
                                        return@forEach
                                    }
                                    temporary.delete()
                                }
                                CaptureTrace.record(CaptureStage.ASSET_READY, windowId, requestedGeneration, request.messageIndex, layer = CaptureLayer.MEDIA)
                                put(
                                    request.messageIndex,
                                    PendingAssetEntity(
                                        sha256 = contentHash,
                                        localPath = output.absolutePath,
                                        mimeType = if (request.lossyWebp) "image/webp" else "image/png",
                                        perceptualHash = differenceHash(cropped),
                                        width = cropped.width,
                                        height = cropped.height,
                                    ),
                                )
                            } finally {
                                cropped.recycle()
                                if (originalCrop !== cropped) originalCrop.recycle()
                            }
                        }
                    }
                }
                return if (!ImageUploadRuntime.isBackgroundWorkAllowed() || !processingAuthorized()) {
                    report("cancelled")
                    emptyMap()
                } else {
                    if (requests.any { it.messageIndex !in assets }) report("failed", SCREENSHOT_ASSET_PREPARATION_FAILED)
                    else report("ready")
                    assets
                }
            } catch (cancelled: CancellationException) {
                report("cancelled")
                throw cancelled
            } catch (_: Exception) {
                if (!ImageUploadRuntime.isBackgroundWorkAllowed() || !processingAuthorized()) report("cancelled")
                else report("failed", SCREENSHOT_ASSET_PREPARATION_FAILED)
                return emptyMap()
            } finally {
                // 包围调度边界：取消时编码块可能根本未运行，仍必须释放系统截图。
                screenshot.bitmap.recycle()
            }
        }
        if (attempt == null) return captureMutex.withLock { captureLocked() }
        // 快速请求不排队持有下一帧；等待输入空闲的已验帧也占用此唯一槽。
        if (!captureMutex.tryLock()) {
            report("cancelled", SCREENSHOT_BACKGROUND_PAUSED)
            return emptyMap()
        }
        return try { captureLocked() } finally { captureMutex.unlock() }
    }
}

private val IntRect.width: Int get() = right - left
private val IntRect.height: Int get() = bottom - top

private fun IntRect.intersection(other: IntRect): IntRect? {
    val result = IntRect(
        left = maxOf(left, other.left),
        top = maxOf(top, other.top),
        right = minOf(right, other.right),
        bottom = minOf(bottom, other.bottom),
    )
    return result.takeIf { it.width > 0 && it.height > 0 }
}
