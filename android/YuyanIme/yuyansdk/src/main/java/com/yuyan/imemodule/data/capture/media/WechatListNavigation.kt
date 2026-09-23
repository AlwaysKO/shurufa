package com.yuyan.imemodule.data.capture.media

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.roundToInt

/** 仅提供候选边界；调用方还必须确认同帧为微信列表。未知布局保留原图。 */
internal fun wechatListNavigationTop(bitmap: Bitmap, density: Float): Int? {
    if (!density.isFinite() || density <= 0 || bitmap.width < 300 * density) return null
    val w = bitmap.width; val h = bitmap.height
    fun luma(x: Int, y: Int): Int {
        val c = bitmap.getPixel(x, y)
        return (((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000
    }
    val step = maxOf(1, density.roundToInt())
    val low = maxOf(1, h - (100 * density).roundToInt(), (h * 0.7).toInt())
    val high = h - (50 * density).roundToInt()
    for (top in high downTo low) {
        val bg = luma(0, top)
        if (abs(bg - luma(0, top - 1)) < 4) continue
        if ((0..32).any { abs(luma(it * (w - 1) / 32, top) - bg) > 12 }) continue
        if ((top until h step step).any { abs(luma(0, it) - bg) > 12 || abs(luma(w - 1, it) - bg) > 12 }) continue
        fun inkCount(cx: Float, halfWidth: Int, start: Int, end: Int, green: Boolean = false): Int {
            var count = 0
            for (dy in start..end) for (dx in -halfWidth..halfWidth) {
                val x = (cx + dx * density).roundToInt(); val y = (top + dy * density).roundToInt()
                if (x !in 0 until w || y !in 0 until h) continue
                if (green) {
                    val c = bitmap.getPixel(x, y)
                    val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
                    if (g >= 100 && g - r >= 40 && g - b >= 20) count++
                } else if (abs(luma(x, y) - bg) >= 28) count++
            }
            return count
        }
        // 四个等距图标与下方标签均存在，各格之间留白，且微信图标处于绿色选中态。
        if (inkCount(w / 8f, 14, 7, 30, green = true) < 20) continue
        if ((0..3).any { tab ->
            val cx = w * (2 * tab + 1) / 8f
            inkCount(cx, 14, 7, 30) < 24 || inkCount(cx, 25, 34, 49) < 12 ||
                listOf(w * tab / 4 + step, w * (tab + 1) / 4 - step).any { x ->
                    (7..49).any { dy -> abs(luma(x, (top + dy * density).roundToInt()) - bg) > 12 }
                }
        }) continue
        return top
    }
    return null
}
