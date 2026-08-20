package com.yuyan.imemodule.data.capture.media

import android.graphics.Bitmap
import android.graphics.Color
import com.yuyan.imemodule.data.capture.sha256
import java.io.ByteArrayOutputStream

internal fun encodeLossless(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use { output ->
    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "无法编码媒体截图" }
    output.toByteArray()
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
