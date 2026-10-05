package com.yuyan.imemodule.data.redpacket

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.SystemClock
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.yuyan.imemodule.data.capture.media.WindowScreenshotResult
import com.yuyan.imemodule.data.capture.media.WindowScreenshotter
import com.yuyan.imemodule.data.capture.media.awaitTitleOcrCompletion
import com.yuyan.imemodule.data.capture.ui.IntRect
import com.yuyan.imemodule.data.collect.ImageUploadRuntime
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.resume

/** 串行读取线程内使用；只在内存识别，既不存图也不上报。 */
internal class PacketVisualReader {
    private fun allowed() = GroupRedPacketAssistant.isInteractionAllowed() && ImageUploadRuntime.isInputIdle()
    private fun requireAllowed() {
        if (!allowed()) throw CancellationException("Packet interaction paused")
    }
    private var lastCaptureAt = 0L
    private val recognizer by lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }

    fun read(service: AccessibilityService, windowId: Int): PacketVisualSnapshot? = runBlocking {
        delay((lastCaptureAt + 350 - SystemClock.uptimeMillis()).coerceAtLeast(0))
        requireAllowed()
        val windows = service.windows
        val rect = Rect()
        try {
            val window = windows.firstOrNull { it.id == windowId && it.isActive } ?: return@runBlocking null
            window.getBoundsInScreen(rect)
        } finally { @Suppress("DEPRECATION") windows.forEach { it.recycle() } }
        if (rect.isEmpty) return@runBlocking null
        val bounds = IntRect(rect.left, rect.top, rect.right, rect.bottom)
        val source = WindowScreenshotter(service, captureAllowed = ::allowed) { it == PACKET_WECHAT }
        var captured = source.capture(windowId, bounds)
        if (captured is WindowScreenshotResult.Failed &&
            captured.errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
            delay(400)
            requireAllowed()
            captured = source.capture(windowId, bounds)
        }
        val capture = captured as? WindowScreenshotResult.Success ?: return@runBlocking null
        val capturedAt = SystemClock.uptimeMillis()
        lastCaptureAt = capturedAt
        val bitmap = capture.bitmap
        try {
            requireAllowed()
            val lines = recognize(bitmap)
            requireAllowed()
            val initial = packetVisualPixels(bitmap, lines)
            val labels = mutableListOf<PacketVisualLine>()
            for (box in initial.orangeRegions.filter { it.left in (bitmap.width * .04).toInt()..(bitmap.width * .20).toInt() }.takeLast(4)) {
                fun inCard(line: PacketVisualLine) = (line.bounds.left + line.bounds.right) / 2 in box.left..box.right &&
                    (line.bounds.top + line.bounds.bottom) / 2 in box.top..box.bottom
                if (lines.any { inCard(it) && it.text.replace(Regex("\\s+"), "") == "微信红包" }) continue
                requireAllowed()
                val footer = IntRect(box.left, box.bottom - ((box.bottom - box.top) * .35).toInt(), box.right, box.bottom)
                val crop = packetLabelCrop(bitmap, footer) ?: continue
                try {
                    labels += recognize(crop).filter { it.text.replace(Regex("\\s+"), "") == "微信红包" }.map {
                        PacketVisualLine("微信红包", IntRect(footer.left + it.bounds.left / 2, footer.top + it.bounds.top / 2,
                            footer.left + it.bounds.right / 2, footer.top + it.bounds.bottom / 2))
                    }
                } finally { crop.recycle() }
            }
            requireAllowed()
            val frame = initial.copy(lines = lines + labels)
            PacketProbe.visual(service,frame)
            val match = parsePacketVisualFrame(frame)
            val signatures = match.page.cards.associateWith { id ->
                val box = match.targets.getValue(id)
                // 内容身份不带坐标，避免 OCR/连通区域几个像素的抖动造成重复领取。
                val words = frame.lines.filter { (it.bounds.left+it.bounds.right)/2 in box.left..box.right &&
                    (it.bounds.top+it.bounds.bottom)/2 in box.top..box.bottom }.joinToString("|") { it.text }
                "${match.page.chatName}|$words"
            }
            PacketVisualSnapshot(match,windowId,bounds,capture.originX,capture.originY,capturedAt,signatures)
        } finally { bitmap.recycle() }
    }
    private suspend fun recognize(bitmap: android.graphics.Bitmap): List<PacketVisualLine> = awaitTitleOcrCompletion {
        requireAllowed()
        suspendCancellableCoroutine<List<PacketVisualLine>> { continuation ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result ->
                    if (continuation.isActive) continuation.resume(result.textBlocks.flatMap { block ->
                        block.lines.mapNotNull { line -> line.boundingBox?.let {
                            PacketVisualLine(line.text, IntRect(it.left, it.top, it.right, it.bottom))
                        } }
                    })
                }
                .addOnFailureListener { if (continuation.isActive) continuation.resume(emptyList()) }
                .addOnCanceledListener { if (continuation.isActive) continuation.resume(emptyList()) }
        }
    }
}
