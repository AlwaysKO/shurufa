package com.yuyan.imemodule.data.capture.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.yuyan.imemodule.data.capture.db.PendingAssetEntity
import android.graphics.Color
import com.yuyan.imemodule.data.capture.sha256
import java.nio.ByteBuffer

/** 标题字形的全分辨率二值 SHA-256；不是容易把相似汉字合并的低分辨率 dHash。 */
internal fun wechatTitlePixelSignature(bitmap: Bitmap, line: OcrTextLine): String? {
    val left = line.left.coerceIn(0, bitmap.width)
    val right = line.right.coerceIn(0, bitmap.width)
    val top = line.top.coerceIn(0, bitmap.height)
    val bottom = line.bottom.coerceIn(0, bitmap.height)
    val width = right - left
    val height = bottom - top
    if (width < 2 || height < 2) return null
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, left, top, width, height)
    val luminance = pixels.map { (Color.red(it) * 299 + Color.green(it) * 587 + Color.blue(it) * 114) / 1000 }
    val min = luminance.minOrNull() ?: return null
    val max = luminance.maxOrNull() ?: return null
    if (max - min < 64) return null
    val threshold = (min + max) / 2
    // 标题区域背景占多数，同时兼容深色与浅色。去掉空白，避免 OCR 框一两像素的浮动。
    val lightBackground = luminance.count { it > threshold } > luminance.size / 2
    val ink = BooleanArray(pixels.size) { if (lightBackground) luminance[it] <= threshold else luminance[it] > threshold }
    var x0 = width; var x1 = -1; var y0 = height; var y1 = -1
    ink.forEachIndexed { index, present ->
        if (present) {
            val x = index % width; val y = index / width
            x0 = minOf(x0, x); x1 = maxOf(x1, x); y0 = minOf(y0, y); y1 = maxOf(y1, y)
        }
    }
    if (x1 < x0 || y1 < y0) return null
    val bytes = ByteArray(8 + (x1 - x0 + 1) * (y1 - y0 + 1))
    ByteBuffer.wrap(bytes).putInt(x1 - x0 + 1).putInt(y1 - y0 + 1)
    var offset = 8
    for (y in y0..y1) for (x in x0..x1) bytes[offset++] = if (ink[y * width + x]) 1 else 0
    return sha256(bytes)
}

internal fun capturedTitlePixelSignature(asset: PendingAssetEntity): String? {
    val bitmap = BitmapFactory.decodeFile(asset.localPath) ?: return null
    return try { wechatTitlePixelSignature(bitmap, OcrTextLine("", 0, 0, bitmap.width, bitmap.height)) }
    finally { bitmap.recycle() }
}
