package com.yuyan.imemodule.data.capture.media

import android.graphics.Bitmap
import com.yuyan.imemodule.data.capture.ScreenshotContentReason
import com.yuyan.imemodule.data.capture.ui.IntRect
import java.security.MessageDigest

/** 只持有摘要，不持有聊天图片；顺序和相同块的出现次数均保留。 */
data class ScreenshotContentBlocks(
    val width: Int,
    val height: Int,
    val hashes: List<String>,
    val rowHashes: ByteArray = byteArrayOf(),
    val hasClippedEdges: Boolean = false,
)

internal const val SCREENSHOT_ROW_HASH_BYTES = 32

/** 仅由微信标题边界校验提供；行颜色只用于辨认空白，原像素仍完整参与摘要。 */
internal data class ScreenshotTopBackground(val color: Int, val rowColors: Map<Int, Int>)

internal fun screenshotContentBlocks(bitmap: Bitmap, bounds: IntRect,
    verifiedTopBackground: ScreenshotTopBackground? = null,
    onReason: (ScreenshotContentReason) -> Unit = {}): ScreenshotContentBlocks? {
    fun reject(reason: ScreenshotContentReason): ScreenshotContentBlocks? { onReason(reason); return null }
    if (bitmap.isRecycled || bounds.left < 0 || bounds.top < 0 || bounds.right > bitmap.width ||
        bounds.bottom > bitmap.height || bounds.right - bounds.left < 16 || bounds.bottom - bounds.top !in 16..8192) return reject(ScreenshotContentReason.INVALID_BOUNDS)
    val background = verifiedTopBackground?.color ?: bitmap.getPixel(bounds.left, bounds.top)
    val channels = listOf((background shr 16) and 255, (background shr 8) and 255, background and 255)
    // 标准纯色聊天背景才有可靠空白分隔；壁纸、渐变及复杂布局不做推断。
    if (channels.max() - channels.min() > 8) return reject(ScreenshotContentReason.BACKGROUND_UNVERIFIED)
    val row = IntArray(bounds.right - bounds.left)
    val rowBytes = ByteArray(row.size * 4)
    val rowHashes = ByteArray((bounds.bottom - bounds.top) * SCREENSHOT_ROW_HASH_BYTES)
    val rowDigest = MessageDigest.getInstance("SHA-256")
    val spans = mutableListOf<IntRange>()
    var start = -1
    for (y in bounds.top until bounds.bottom) {
        bitmap.getPixels(row, 0, row.size, bounds.left, y, row.size, 1)
        val rowBackground = verifiedTopBackground?.rowColors?.get(y) ?: background
        if (row.first() != rowBackground || row.last() != rowBackground) return reject(ScreenshotContentReason.EDGE_CONTENT_UNVERIFIED)
        for (x in row.indices) {
            val pixel = row[x]
            val offset = x * 4
            rowBytes[offset] = (pixel ushr 24).toByte()
            rowBytes[offset + 1] = (pixel ushr 16).toByte()
            rowBytes[offset + 2] = (pixel ushr 8).toByte()
            rowBytes[offset + 3] = pixel.toByte()
        }
        rowDigest.digest(rowBytes).copyInto(rowHashes, (y - bounds.top) * SCREENSHOT_ROW_HASH_BYTES)
        val occupied = row.any { it != rowBackground }
        if (occupied && start < 0) start = y
        if (!occupied && start >= 0) { spans += start until y; start = -1 }
        if (spans.size > 128) return reject(ScreenshotContentReason.TOO_MANY_BLOCKS)
    }
    if (start >= 0) spans += start until bounds.bottom
    if (spans.isEmpty()) return reject(ScreenshotContentReason.NO_BLOCKS)
    if (spans.size > 128) return reject(ScreenshotContentReason.TOO_MANY_BLOCKS)
    // 边缘碎块不充当完整锚点，但每个像素仍在逐行摘要里，不能忽略后再比较。
    val clipped = spans.first().first == bounds.top || spans.last().last == bounds.bottom - 1
    val complete = spans.filter { it.first > bounds.top && it.last < bounds.bottom - 1 }
    val hashes = complete.map { span ->
        exactPixelHash(bitmap, IntRect(bounds.left, span.first, bounds.right, span.last + 1)) ?: return reject(ScreenshotContentReason.INVALID_BOUNDS)
    }
    return ScreenshotContentBlocks(row.size, bounds.bottom - bounds.top, hashes, rowHashes, clipped)
}
