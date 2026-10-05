package com.yuyan.imemodule.data.redpacket

import android.graphics.Bitmap
import android.graphics.Color
import com.yuyan.imemodule.data.capture.ui.IntRect

/** 调用方传入已确认橙色卡片的标签区域；不扩展到整张截图。 */
internal fun packetLabelCrop(bitmap: Bitmap, box: IntRect): Bitmap? {
    if (bitmap.isRecycled || box.left < 0 || box.top < 0 || box.right > bitmap.width ||
        box.bottom > bitmap.height || box.right <= box.left || box.bottom <= box.top) return null
    val width = box.right - box.left
    val height = box.bottom - box.top
    val original = IntArray(width * height)
    bitmap.getPixels(original, 0, width, box.left, box.top, width, height)
    val scaledWidth = width * 2
    val output = IntArray(scaledWidth * height * 2)
    for (y in 0 until height) for (x in 0 until width) {
        val pixel = original[y * width + x]
        val red = pixel shr 16 and 255
        val green = pixel shr 8 and 255
        val blue = pixel and 255
        val darkest = minOf(red, green, blue)
        val lightest = maxOf(red, green, blue)
        val value = if (pixel ushr 24 >= 220 && darkest >= 180 && lightest - darkest <= 75)
            Color.BLACK else Color.WHITE
        val target = y * 2 * scaledWidth + x * 2
        output[target] = value
        output[target + 1] = value
        output[target + scaledWidth] = value
        output[target + scaledWidth + 1] = value
    }
    return Bitmap.createBitmap(output, scaledWidth, height * 2, Bitmap.Config.ARGB_8888)
}
