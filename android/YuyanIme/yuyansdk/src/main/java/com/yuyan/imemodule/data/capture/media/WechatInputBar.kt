package com.yuyan.imemodule.data.capture.media

import android.graphics.Bitmap
import kotlin.math.*

/** 微信单行输入工具栏的严格视觉适配。未知/多行布局不裁，不使用正文OCR。 */
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
    fun sendButton(cy: Float, bg: Int): Boolean {
        fun green(x: Int, y: Int): Boolean {
            if (x !in 0 until w || y !in 0 until h) return false
            val c = bitmap.getPixel(x, y)
            val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
            return g >= 100 && g - r >= 40 && g - b >= 20
        }
        // 发送按钮位于右侧，检查完整背景带、宽度及外侧留白，不能仅凭绿色块裁掉消息。
        val y = (cy - 12 * density).roundToInt()
        val band = ((w - 80 * density).roundToInt() until w).filter { green(it, y) }
        val left = band.firstOrNull() ?: return false
        val right = band.last()
        if (right - left + 1 !in (42 * density).roundToInt()..(64 * density).roundToInt() ||
            w - right !in (4 * density).roundToInt()..(12 * density).roundToInt()) return false
        if (listOf(-12f, 12f).any { dy ->
            ((left + 4 * density).roundToInt()..(right - 4 * density).roundToInt()).any {
                !green(it, (cy + dy * density).roundToInt())
            }
        }) return false
        val center = (left + right) / 2f
        if (listOf(-20f, 20f).any {
            val py = (cy + it * density).roundToInt()
            py !in 0 until h || abs(luma(center.roundToInt(), py) - bg) > 8
        }) return false
        // 两个字形都要有明/暗前景；纯色按钮、气泡和缺少相邻表情图标的候选不通过。
        for (side in listOf(-1, 1)) {
            val hasLabel = (2..14).any { dx -> (-8..8).any { dy ->
                val c = bitmap.getPixel((center + side * dx * density).roundToInt(), (cy + dy * density).roundToInt())
                val channels = listOf((c shr 16) and 255, (c shr 8) and 255, c and 255)
                channels.max() - channels.min() <= 40 && (channels.min() >= 200 || channels.max() <= 80)
            } }
            if (!hasLabel) return false
        }
        return (18..26).any { ring(left - it * density, cy, bg) }
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
        if (!ring(23 * density, cy, bg)) continue
        if (sendButton(cy, bg)) return top
        if (!ring(right, cy, bg)) continue
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
