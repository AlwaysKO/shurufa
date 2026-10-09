package com.yuyan.imemodule.data.redpacket

import android.graphics.Bitmap
import android.graphics.Color
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.yuyan.imemodule.data.capture.media.awaitTitleOcrCompletion
import com.yuyan.imemodule.data.capture.ui.IntRect
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 独立副屏识别器，仅消费内存图片，图片所有权来自 capture。 */
internal class SilentPacketReader(private val recognize: (suspend (Bitmap) -> List<PacketVisualLine>)? = null) {
    private val recognizer = lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }

    /** 用程序生成的空白图预热本地模型，不使用微信画面或用户数据。 */
    suspend fun prepare(allowed: () -> Boolean): Boolean {
        currentCoroutineContext().ensureActive()
        if (!allowed()) return false
        val blank = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        try {
            blank.eraseColor(Color.WHITE)
            currentCoroutineContext().ensureActive()
            if (!allowed()) return false
            text(blank)
            currentCoroutineContext().ensureActive()
            return allowed()
        } finally { if (!blank.isRecycled) blank.recycle() }
    }

    suspend fun read(frame: SilentPacketFrame, allowed: () -> Boolean): PacketVisualSnapshot? {
        val bitmap = frame.bitmap
        try {
            currentCoroutineContext().ensureActive()
            if (!allowed() || bitmap.isRecycled || frame.displayId <= 0 || frame.frameId <= 0 ||
                bitmap.width !in 1..4096 || bitmap.height !in 1..8192 ||
                bitmap.width.toLong() * bitmap.height > 8_388_608) return null
            val lines = text(bitmap)
            currentCoroutineContext().ensureActive()
            if (!allowed()) return null
            val initial = packetVisualPixels(bitmap, lines)
            if (!allowed()) return null
            val labels = mutableListOf<PacketVisualLine>()
            for (box in initial.orangeRegions.filter {
                it.left in (bitmap.width * .04).toInt()..(bitmap.width * .20).toInt()
            }.takeLast(4)) {
                fun inCard(line: PacketVisualLine) = (line.bounds.left + line.bounds.right) / 2 in box.left..box.right &&
                    (line.bounds.top + line.bounds.bottom) / 2 in box.top..box.bottom
                if (lines.any { inCard(it) && it.text.replace(Regex("\\s+"), "") == "微信红包" }) continue
                currentCoroutineContext().ensureActive()
                if (!allowed()) return null
                val footer = IntRect(box.left, box.bottom - ((box.bottom - box.top) * .35).toInt(), box.right, box.bottom)
                val crop = packetLabelCrop(bitmap, footer) ?: continue
                try {
                    labels += text(crop).filter { it.text.replace(Regex("\\s+"), "") == "微信红包" }.map {
                        PacketVisualLine("微信红包", IntRect(footer.left + it.bounds.left / 2, footer.top + it.bounds.top / 2,
                            footer.left + it.bounds.right / 2, footer.top + it.bounds.bottom / 2))
                    }
                } finally { crop.recycle() }
            }
            currentCoroutineContext().ensureActive()
            if (!allowed()) return null
            val framePixels = initial.copy(lines = lines + labels)
            val match = parsePacketVisualFrame(framePixels)
            val signatures = match.targets.mapValues { (id, box) ->
                val words = framePixels.lines.filter { line ->
                    match.page.packetPanel || id == "visual:info" && line.bounds.bottom <= bitmap.height * .16 ||
                        (line.bounds.left + line.bounds.right) / 2 in box.left..box.right &&
                        (line.bounds.top + line.bounds.bottom) / 2 in box.top..box.bottom
                }.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
                    .joinToString("|") { it.text.replace(Regex("\\s+"), "") }
                "${match.page.chatName}|$words"
            }
            return PacketVisualSnapshot(match, frame.displayId, IntRect(0, 0, bitmap.width, bitmap.height),
                0, 0, frame.capturedAt, signatures)
        } finally { if (!bitmap.isRecycled) bitmap.recycle() }
    }

    private suspend fun text(bitmap: Bitmap): List<PacketVisualLine> = awaitTitleOcrCompletion {
        recognize?.invoke(bitmap) ?: suspendCancellableCoroutine { continuation ->
            recognizer.value.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result ->
                    if (continuation.isActive) continuation.resume(result.textBlocks.flatMap { block ->
                        block.lines.mapNotNull { line -> line.boundingBox?.let {
                            PacketVisualLine(line.text, IntRect(it.left, it.top, it.right, it.bottom))
                        } }
                    })
                }
                .addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Recognition failed")) }
                .addOnCanceledListener { if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Recognition cancelled")) }
        }
    }

    fun close() { if (recognizer.isInitialized()) recognizer.value.close() }
}
