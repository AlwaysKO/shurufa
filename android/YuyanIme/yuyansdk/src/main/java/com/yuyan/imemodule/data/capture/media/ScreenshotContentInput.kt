package com.yuyan.imemodule.data.capture.media

import android.graphics.Bitmap
import com.yuyan.imemodule.data.capture.CaptureLayer
import com.yuyan.imemodule.data.capture.CaptureStage
import com.yuyan.imemodule.data.capture.CaptureTrace
import com.yuyan.imemodule.data.capture.ScreenshotContentReason
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
    var reason: ScreenshotContentReason = ScreenshotContentReason.NOT_REQUESTED
        private set
    fun captureFrom(bitmap: Bitmap, density: Float = 1f, bodyBoundaryVerified: Boolean = true) {
        sha256 = exactPixelHash(bitmap, IntRect(0, bodyTopPx, bitmap.width, bitmap.height))
        wechatListSha256 = if (detectWechatList) wechatListNavigationTop(bitmap, density)?.let {
            exactPixelHash(bitmap, IntRect(0, bodyTopPx, bitmap.width, it))
        } else null
        reason = ScreenshotContentReason.NOT_REQUESTED
        val bounds = when {
            !detectBlocks -> null
            !bodyBoundaryVerified -> { reason = ScreenshotContentReason.BODY_BOUNDARY_UNVERIFIED; null }
            wechatListSha256 != null -> { reason = ScreenshotContentReason.CONVERSATION_LIST; null }
            detectWechatBody -> wechatScreenshotBodyBounds(bitmap, bodyTopPx, density) { reason = it }
            else -> ScreenshotBodyRegion(IntRect(0, bodyTopPx, bitmap.width, bitmap.height))
        }
        blocks = bounds?.let { screenshotContentBlocks(bitmap, it.bounds, it.background) { cause -> reason = cause } }
        if (blocks != null) reason = ScreenshotContentReason.READY
        CaptureTrace.record(CaptureStage.CONTENT_DECISION, layer = CaptureLayer.MEDIA, reason = reason)
    }
}

private data class ScreenshotBodyRegion(val bounds: IntRect, val background: ScreenshotTopBackground? = null)

/** 只排除已验证的微信最右薄滚动条；无条也使用相同宽度，未知边缘内容不参与块去重。 */
private fun wechatScreenshotBodyBounds(bitmap: Bitmap, bodyTop: Int, density: Float,
    onReason: (ScreenshotContentReason) -> Unit): ScreenshotBodyRegion? {
    fun reject(reason: ScreenshotContentReason): ScreenshotBodyRegion? { onReason(reason); return null }
    if (bitmap.isRecycled || !density.isFinite() || density <= 0 || bodyTop !in 0 until bitmap.height) return reject(ScreenshotContentReason.INVALID_BOUNDS)
    val laneWidth = kotlin.math.ceil(5 * density).toInt()
    val margin = kotlin.math.ceil(density).toInt().coerceAtLeast(1)
    val right = bitmap.width - laneWidth
    if (right < 16 || right - margin <= 0) return reject(ScreenshotContentReason.INVALID_BOUNDS)
    fun channels(pixel: Int) = intArrayOf((pixel shr 16) and 255, (pixel shr 8) and 255, pixel and 255)
    fun safeEdgesMatch(y: Int, color: Int): Boolean = (0 until margin).all {
        bitmap.getPixel(it, y) == color && bitmap.getPixel(right - margin + it, y) == color
    }
    fun titleShadow(y: Int, background: Int): Int? {
        if (y !in bodyTop until minOf(bitmap.height, bodyTop + margin)) return null
        val color = bitmap.getPixel(0, y)
        val rgb = channels(color)
        val bg = channels(background)
        return color.takeIf { color != background && (color ushr 24) == 255 && rgb.max() == rgb.min() &&
            rgb.indices.all { kotlin.math.abs(rgb[it] - bg[it]) <= 1 } && safeEdgesMatch(y, color) }
    }
    var top = bodyTop
    var shadowBackground: Int? = null
    val probeY = bodyTop + margin
    if (probeY < bitmap.height && bitmap.getPixel(0, top) != bitmap.getPixel(0, probeY)) {
        val stableBackground = bitmap.getPixel(0, probeY)
        val stable = channels(stableBackground)
        if ((stableBackground ushr 24) != 255 || stable.max() - stable.min() > 4) return reject(ScreenshotContentReason.BODY_BOUNDARY_UNVERIFIED)
        if (titleShadow(top, stableBackground) != null) {
            if (probeY + margin > bitmap.height || (probeY until probeY + margin).any {
                !safeEdgesMatch(it, stableBackground)
            }) return reject(ScreenshotContentReason.BODY_BOUNDARY_UNVERIFIED)
            // 首行可能含正文；只确认左右背景，不裁行，也不改动行内像素。
            shadowBackground = stableBackground
        } else {
            if (probeY + margin > bitmap.height || (probeY until probeY + margin).any {
                bitmap.getPixel(0, it) != stableBackground || bitmap.getPixel(bitmap.width - 1, it) != stableBackground
            }) return reject(ScreenshotContentReason.BODY_BOUNDARY_UNVERIFIED)
            val separatorRow = IntArray(bitmap.width)
            while (top < probeY && bitmap.getPixel(0, top) != stableBackground) {
                val color = bitmap.getPixel(0, top)
                val rgb = channels(color)
                if ((color ushr 24) != 255 || rgb.max() - rgb.min() > 4 ||
                    rgb.indices.any { kotlin.math.abs(rgb[it] - stable[it]) > 32 }) return reject(ScreenshotContentReason.BODY_BOUNDARY_UNVERIFIED)
                bitmap.getPixels(separatorRow, 0, separatorRow.size, 0, top, separatorRow.size, 1)
                // 标题底部最多1dp的全宽分隔行；只要出现正文像素就保留原图、放弃块去重。
                if (separatorRow.any { it != color }) return reject(ScreenshotContentReason.BODY_BOUNDARY_UNVERIFIED)
                top++
            }
        }
    }
    val background = shadowBackground ?: bitmap.getPixel(0, top)
    val bg = channels(background)
    if ((background ushr 24) != 255 || bg.max() - bg.min() > 4) return reject(ScreenshotContentReason.BACKGROUND_UNVERIFIED)
    var bottom = bitmap.height
    if (bitmap.getPixel(0, bottom - 1) != background) {
        val separatorRow = IntArray(bitmap.width)
        while (bottom > maxOf(top, bitmap.height - margin) && bitmap.getPixel(0, bottom - 1) != background) {
            val color = bitmap.getPixel(0, bottom - 1)
            val rgb = channels(color)
            if ((color ushr 24) != 255 || rgb.max() - rgb.min() > 4 ||
                rgb.indices.any { kotlin.math.abs(rgb[it] - bg[it]) > 32 }) return reject(ScreenshotContentReason.BODY_BOUNDARY_UNVERIFIED)
            bitmap.getPixels(separatorRow, 0, separatorRow.size, 0, bottom - 1, separatorRow.size, 1)
            // 已验证输入栏上缘的最多1dp分隔线；末行含任意正文像素时不能丢弃。
            if (separatorRow.any { it != color }) return reject(ScreenshotContentReason.BODY_BOUNDARY_UNVERIFIED)
            bottom--
        }
    }
    val row = IntArray(laneWidth + margin)
    var startY = -1
    var endY = -1
    var minimumWidth = laneWidth
    var maximumWidth = 0
    var barShade: Int? = null
    val topRowColors = mutableMapOf<Int, Int>()
    for (y in top until bottom) {
        bitmap.getPixels(row, 0, row.size, right - margin, y, row.size, 1)
        val rowBackground = titleShadow(y, background)?.also { topRowColors[y] = it } ?: background
        if ((0 until margin).any { row[it] != rowBackground }) return reject(ScreenshotContentReason.SCROLLBAR_UNVERIFIED)
        val first = (margin until row.size).firstOrNull { row[it] != rowBackground } ?: continue
        // 空行将长条分开、末列仍为空白、过短笔画，都不能当作滚动条丢弃。
        if (endY >= 0 && y != endY + 1) return reject(ScreenshotContentReason.SCROLLBAR_UNVERIFIED)
        if (startY < 0) startY = y
        endY = y
        val width = row.size - first
        minimumWidth = minOf(minimumWidth, width)
        maximumWidth = maxOf(maximumWidth, width)
        for (x in first until row.size) {
            val pixel = row[x]
            val rgb = channels(pixel)
            if (pixel == rowBackground || (pixel ushr 24) != 255 || rgb.max() - rgb.min() > 4 ||
                rgb.indices.any { kotlin.math.abs(rgb[it] - bg[it]) > 96 }) return reject(ScreenshotContentReason.SCROLLBAR_UNVERIFIED)
        }
        val shade = channels(row.last()).sum() / 3
        if (kotlin.math.abs(shade - bg.sum() / 3) < 16) return reject(ScreenshotContentReason.SCROLLBAR_UNVERIFIED)
        if (barShade != null && kotlin.math.abs(shade - barShade) > 8) return reject(ScreenshotContentReason.SCROLLBAR_UNVERIFIED)
        barShade = barShade ?: shade
    }
    if (startY >= 0 && (endY - startY + 1 < 48 * density || minimumWidth < margin ||
            maximumWidth - minimumWidth > margin)) return reject(ScreenshotContentReason.SCROLLBAR_UNVERIFIED)
    return ScreenshotBodyRegion(IntRect(0, top, right, bottom), ScreenshotTopBackground(background, topRowColors))
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
