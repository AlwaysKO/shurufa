package com.yuyan.imemodule.data.capture.media

import android.graphics.Bitmap
import com.yuyan.imemodule.data.capture.ui.IntRect

/** 单次媒体请求的精确内容摘要；不持有图像，不读取文字。 */
class ScreenshotContentInput(
    private val bodyTopPx: Int,
    private val detectWechatList: Boolean = false,
    private val detectBlocks: Boolean = false,
    private val detectWechatBody: Boolean = false,
) {
    var sha256: String? = null
        private set
    var wechatListSha256: String? = null
        private set
    var blocks: ScreenshotContentBlocks? = null
        private set
    fun captureFrom(bitmap: Bitmap, density: Float = 1f, bodyBoundaryVerified: Boolean = true) {
        sha256 = exactPixelHash(bitmap, IntRect(0, bodyTopPx, bitmap.width, bitmap.height))
        wechatListSha256 = if (detectWechatList) wechatListNavigationTop(bitmap, density)?.let {
            exactPixelHash(bitmap, IntRect(0, bodyTopPx, bitmap.width, it))
        } else null
        val bounds = if (!detectBlocks || !bodyBoundaryVerified || wechatListSha256 != null) null
            else if (detectWechatBody) wechatScreenshotBodyBounds(bitmap, bodyTopPx, density)
            else IntRect(0, bodyTopPx, bitmap.width, bitmap.height)
        blocks = bounds?.let { screenshotContentBlocks(bitmap, it) }
    }
}

/** 只排除已验证的微信最右薄滚动条；无条也使用相同宽度，未知边缘内容不参与块去重。 */
private fun wechatScreenshotBodyBounds(bitmap: Bitmap, bodyTop: Int, density: Float): IntRect? {
    if (bitmap.isRecycled || !density.isFinite() || density <= 0 || bodyTop !in 0 until bitmap.height) return null
    val laneWidth = kotlin.math.ceil(5 * density).toInt()
    val margin = kotlin.math.ceil(density).toInt().coerceAtLeast(1)
    val right = bitmap.width - laneWidth
    if (right < 16 || right - margin <= 0) return null
    fun channels(pixel: Int) = intArrayOf((pixel shr 16) and 255, (pixel shr 8) and 255, pixel and 255)
    var top = bodyTop
    val probeY = bodyTop + margin
    if (probeY < bitmap.height && bitmap.getPixel(0, top) != bitmap.getPixel(0, probeY)) {
        val stableBackground = bitmap.getPixel(0, probeY)
        val stable = channels(stableBackground)
        if ((stableBackground ushr 24) != 255 || stable.max() - stable.min() > 4) return null
        if (probeY + margin > bitmap.height || (probeY until probeY + margin).any {
            bitmap.getPixel(0, it) != stableBackground || bitmap.getPixel(bitmap.width - 1, it) != stableBackground
        }) return null
        val separatorRow = IntArray(bitmap.width)
        while (top < probeY && bitmap.getPixel(0, top) != stableBackground) {
            val color = bitmap.getPixel(0, top)
            val rgb = channels(color)
            if ((color ushr 24) != 255 || rgb.max() - rgb.min() > 4 ||
                rgb.indices.any { kotlin.math.abs(rgb[it] - stable[it]) > 32 }) return null
            bitmap.getPixels(separatorRow, 0, separatorRow.size, 0, top, separatorRow.size, 1)
            // 标题底部最多1dp的全宽分隔行；只要出现正文像素就保留原图、放弃块去重。
            if (separatorRow.any { it != color }) return null
            top++
        }
    }
    val background = bitmap.getPixel(0, top)
    val bg = channels(background)
    if ((background ushr 24) != 255 || bg.max() - bg.min() > 4) return null
    var bottom = bitmap.height
    if (bitmap.getPixel(0, bottom - 1) != background) {
        val separatorRow = IntArray(bitmap.width)
        while (bottom > maxOf(top, bitmap.height - margin) && bitmap.getPixel(0, bottom - 1) != background) {
            val color = bitmap.getPixel(0, bottom - 1)
            val rgb = channels(color)
            if ((color ushr 24) != 255 || rgb.max() - rgb.min() > 4 ||
                rgb.indices.any { kotlin.math.abs(rgb[it] - bg[it]) > 32 }) return null
            bitmap.getPixels(separatorRow, 0, separatorRow.size, 0, bottom - 1, separatorRow.size, 1)
            // 已验证输入栏上缘的最多1dp分隔线；末行含任意正文像素时不能丢弃。
            if (separatorRow.any { it != color }) return null
            bottom--
        }
    }
    val row = IntArray(laneWidth + margin)
    var startY = -1
    var endY = -1
    var minimumWidth = laneWidth
    var maximumWidth = 0
    var barShade: Int? = null
    for (y in top until bottom) {
        bitmap.getPixels(row, 0, row.size, right - margin, y, row.size, 1)
        if ((0 until margin).any { row[it] != background }) return null
        val first = (margin until row.size).firstOrNull { row[it] != background } ?: continue
        // 空行将长条分开、末列仍为空白、过短笔画，都不能当作滚动条丢弃。
        if (endY >= 0 && y != endY + 1) return null
        if (startY < 0) startY = y
        endY = y
        val width = row.size - first
        minimumWidth = minOf(minimumWidth, width)
        maximumWidth = maxOf(maximumWidth, width)
        for (x in first until row.size) {
            val pixel = row[x]
            val rgb = channels(pixel)
            if (pixel == background || (pixel ushr 24) != 255 || rgb.max() - rgb.min() > 4 ||
                rgb.indices.any { kotlin.math.abs(rgb[it] - bg[it]) > 96 }) return null
        }
        val shade = channels(row.last()).sum() / 3
        if (kotlin.math.abs(shade - bg.sum() / 3) < 16) return null
        if (barShade != null && kotlin.math.abs(shade - barShade) > 8) return null
        barShade = barShade ?: shade
    }
    if (startY >= 0 && (endY - startY + 1 < 48 * density || minimumWidth < margin ||
            maximumWidth - minimumWidth > margin)) return null
    return IntRect(0, top, right, bottom)
}

internal fun exactPixelHash(bitmap: Bitmap, bounds: IntRect): String? {
    if (bitmap.isRecycled || bounds.left < 0 || bounds.top < 0 || bounds.right > bitmap.width ||
        bounds.bottom > bitmap.height || bounds.right <= bounds.left || bounds.bottom <= bounds.top) return null
    val width = bounds.right - bounds.left
    val height = bounds.bottom - bounds.top
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    digest.update(java.nio.ByteBuffer.allocate(8).putInt(width).putInt(height).array())
    val pixels = IntArray(width)
    val bytes = ByteArray(width * 4)
    for (y in bounds.top until bounds.bottom) {
        bitmap.getPixels(pixels, 0, width, bounds.left, y, width, 1)
        for (x in pixels.indices) {
            val pixel = pixels[x]; val offset = x * 4
            bytes[offset] = (pixel ushr 24).toByte()
            bytes[offset + 1] = (pixel ushr 16).toByte()
            bytes[offset + 2] = (pixel ushr 8).toByte()
            bytes[offset + 3] = pixel.toByte()
        }
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}
