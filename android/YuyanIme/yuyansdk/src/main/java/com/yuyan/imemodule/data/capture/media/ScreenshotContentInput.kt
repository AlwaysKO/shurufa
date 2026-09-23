package com.yuyan.imemodule.data.capture.media

import android.graphics.Bitmap
import com.yuyan.imemodule.data.capture.ui.IntRect

/** 单次媒体请求的精确内容摘要；不持有图像，不读取文字。 */
class ScreenshotContentInput(private val bodyTopPx: Int, private val detectWechatList: Boolean = false) {
    var sha256: String? = null
        private set
    var wechatListSha256: String? = null
        private set
    fun captureFrom(bitmap: Bitmap, density: Float = 1f) {
        sha256 = exactPixelHash(bitmap, IntRect(0, bodyTopPx, bitmap.width, bitmap.height))
        wechatListSha256 = if (detectWechatList) wechatListNavigationTop(bitmap, density)?.let {
            exactPixelHash(bitmap, IntRect(0, bodyTopPx, bitmap.width, it))
        } else null
    }
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
