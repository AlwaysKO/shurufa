package com.yuyan.imemodule.data.capture.media

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import com.yuyan.imemodule.data.capture.sha256
import java.io.ByteArrayOutputStream

internal fun encodeLossless(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use { output ->
    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "无法编码媒体截图" }
    output.toByteArray()
}

/**
 * 在新附件计算 SHA256 前编码；不缩小原始像素，避免损坏中文小字。
 * 256 KiB 是软目标：最多两次编码，质量最低 72，宁可超目标也不继续毁字压缩。
 * 由 WindowMediaCapturer 的后台处理 dispatcher 调用，不能放到输入/主线程。
 */
@Suppress("DEPRECATION")
internal fun encodeWebp(bitmap: Bitmap): ByteArray {
    val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Bitmap.CompressFormat.WEBP_LOSSY
    } else {
        Bitmap.CompressFormat.WEBP
    }
    fun encode(quality: Int): ByteArray = ByteArrayOutputStream().use { output ->
        check(bitmap.compress(format, quality, output)) { "无法压缩聊天截图" }
        output.toByteArray()
    }
    val normal = encode(78)
    if (normal.size <= 256 * 1024) return normal
    val smaller = encode(72)
    return if (smaller.size < normal.size) smaller else normal
}

fun imageContentSha256(bitmap: Bitmap): String = sha256(encodeLossless(bitmap))

fun differenceHash(bitmap: Bitmap): String {
    val sample = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
    var hash = 0L
    try {
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                hash = hash shl 1
                if (luminance(sample.getPixel(x, y)) > luminance(sample.getPixel(x + 1, y))) {
                    hash = hash or 1L
                }
            }
        }
    } finally {
        if (sample !== bitmap) sample.recycle()
    }
    return java.lang.Long.toUnsignedString(hash, 16).padStart(16, '0')
}

fun hammingDistance(first: String, second: String): Int {
    require(first.length == second.length) { "哈希长度必须相同" }
    return first.indices.sumOf { index ->
        val xor = first[index].digitToInt(16) xor second[index].digitToInt(16)
        Integer.bitCount(xor)
    }
}

private fun luminance(color: Int): Int =
    (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000
