package com.yuyan.imemodule.data.capture.page

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs

/**
 * 同一候选帧顶部的小程序胶囊；只复用已知的圆环/中心点/左三点组合，不把普通网页或空菜单当小程序。
 * 仅扫描标准深/浅色导航，按窗口宽度缩放；不识别彩色主题时保守不采，不做后台循环取帧。
 */
internal fun hasWechatMiniAppCapsule(bitmap: Bitmap): Boolean {
    if (bitmap.isRecycled || bitmap.width < 240 || bitmap.height < 320) return false
    val width = bitmap.width
    val cardinal = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
    for (ratio in listOf(.10, .12, .14, .16)) {
        val h = (width * ratio).toInt()
        val gap = (h * .105).toInt()
        val ring = (h * .17).toInt()
        val outside = (h * .235).toInt()
        val step = maxOf(1, h / 48)
        val maxY = minOf((bitmap.height * .18).toInt(), h * 2)
        for (y in outside + 1 until maxY step step) {
            val background = bitmap.getPixel(width - 2, y)
            val r = Color.red(background); val g = Color.green(background); val b = Color.blue(background)
            if (maxOf(r, g, b) - minOf(r, g, b) > 24 || (r + g + b) / 3 in 65..209) continue
            fun ink(x: Int, py: Int): Boolean {
                if (x !in 0 until width || py !in 0 until bitmap.height) return false
                val pixel = bitmap.getPixel(x, py)
                return maxOf(abs(Color.red(pixel) - r), abs(Color.green(pixel) - g), abs(Color.blue(pixel) - b)) > 80
            }
            for (x in width - h..width - h / 2 step step) {
                if (!ink(x, y)) continue
                if (cardinal.all { (dx, dy) -> !ink(x + dx * gap, y + dy * gap) &&
                        ink(x + dx * ring, y + dy * ring) && !ink(x + dx * outside, y + dy * outside) } &&
                    listOf(.93, 1.10, 1.28).all { offset ->
                        val dotX = x - (h * offset).toInt()
                        (-step..step).any { ink(dotX + it, y) }
                    }) return true
            }
        }
    }
    return false
}
