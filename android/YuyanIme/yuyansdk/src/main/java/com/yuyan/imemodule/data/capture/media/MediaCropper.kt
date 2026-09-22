package com.yuyan.imemodule.data.capture.media

import android.content.Context
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
) : MediaAssetCapturer {
    // 三 App 的主视口、空树和通知补偿共享本实例，不各自向系统并发截图。
    private val captureMutex = Mutex()

    override suspend fun capture(
        windowId: Int,
        windowBounds: IntRect,
        requests: List<MediaCaptureRequest>,
    ): Map<Int, PendingAssetEntity> {
        val requestedGeneration = captureGeneration()
        return captureMutex.withLock {
            currentCoroutineContext().ensureActive()
            if (requests.isEmpty() || !captureAllowed() || captureGeneration() != requestedGeneration) return@withLock emptyMap()
            // 系统截图提交后不能撤销。取消也必须等回调收尾，才能放行下一次物理请求。
            val screenshot = withContext(NonCancellable) { screenshotSource.capture(windowId, windowBounds) }
            try {
                currentCoroutineContext().ensureActive()
            } catch (cancelled: CancellationException) {
                if (screenshot is WindowScreenshotResult.Success) screenshot.bitmap.recycle()
                throw cancelled
            }
            if (screenshot !is WindowScreenshotResult.Success) return@withLock emptyMap()

            try {
                withContext(processingDispatcher) {
                    buildMap {
                        requests.forEach { request ->
                            val cropped = cropper.crop(
                                bitmap = screenshot.bitmap,
                                requested = request.bounds,
                                windowBounds = windowBounds,
                                screenshotOriginX = screenshot.originX,
                                screenshotOriginY = screenshot.originY,
                                inputAreaBounds = request.inputAreaBounds,
                            ) ?: return@forEach
                            try {
                                request.titleOcrInput?.captureFrom(cropped)
                                val encoded = if (request.lossyWebp) encodeWebp(cropped) else encodeLossless(cropped)
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
                            }
                        }
                    }
                }
            } finally {
                // 包围调度边界：取消时编码块可能根本未运行，仍必须释放系统截图。
                screenshot.bitmap.recycle()
            }
        }
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
