package com.yuyan.imemodule.data.capture.media

import android.graphics.Bitmap
import kotlin.math.*

/** 已知微信单行输入工具栏的严格视觉适配。未知/多行/无加号布局不裁，不使用正文OCR。 */
internal fun wechatInputBarTop(bitmap: Bitmap, density: Float): Int? {
    if (!density.isFinite() || density <= 0 || bitmap.width < 160 * density) return null
    val w = bitmap.width; val h = bitmap.height
    fun luma(x: Int, y: Int): Int {
        val c = bitmap.getPixel(x, y)
        return (((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000
    }
    fun nearInk(x: Float, y: Float, bg: Int): Boolean {
        val radius = ceil(density).toInt()
        for (dy in -radius..radius) for (dx in -radius..radius) {
            val px = x.roundToInt() + dx; val py = y.roundToInt() + dy
            if (px in 0 until w && py in 0 until h && abs(luma(px, py) - bg) >= 64) return true
        }
        return false
    }
    fun ring(cx: Float, cy: Float, bg: Int): Boolean = (0 until 8).all {
        val angle = it * PI / 4
        nearInk(cx + (cos(angle) * 11 * density).toFloat(), cy + (sin(angle) * 11 * density).toFloat(), bg)
    }
    val low = maxOf((h * 0.7).toInt(), h - (90 * density).roundToInt(), 1)
    val high = h - (42 * density).roundToInt()
    if (high < low) return null
    // 从底部向上，仅接受最下方完整工具栏；全宽边界及两侧背景必须连续到底。
    for (top in high downTo low) {
        val bg = luma(0, top)
        if (abs(bg - luma(0, top - 1)) < 6) continue
        if ((0..32).any { abs(luma(it * (w - 1) / 32, top) - bg) > 8 }) continue
        if ((top until h step maxOf(1, density.roundToInt())).any {
            abs(luma(0, it) - bg) > 8 || abs(luma(w - 1, it) - bg) > 8
        }) continue
        val cy = top + 26 * density
        val right = w - 22 * density
        if (!ring(23 * density, cy, bg) || !ring(right, cy, bg)) continue
        if (!listOf(0f to 0f, -5f to 0f, 5f to 0f, 0f to -5f, 0f to 5f).all {
            nearInk(right + it.first * density, cy + it.second * density, bg)
        }) continue
        // 加号斜角应为空白，排除只有圆环、头像或实心按钮的候选。
        if (listOf(-5 to -5, -5 to 5, 5 to -5, 5 to 5).any {
            abs(luma((right + it.first * density).roundToInt(), (cy + it.second * density).roundToInt()) - bg) > 32
        }) continue
        return top
    }
    return null
}
