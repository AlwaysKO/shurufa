package com.yuyan.imemodule.data.redpacket

import android.graphics.Bitmap
import com.yuyan.imemodule.data.capture.ui.IntRect
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** 颜色只产生候选区域，领取资格仍由文字、群资料和当前任务共同确认。 */
internal fun packetVisualPixels(bitmap: Bitmap, lines: List<PacketVisualLine>): PacketVisualFrame {
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    val step = max(1, min(width, height) / 320)
    val columns = (width + step - 1) / step
    val rows = (height + step - 1) / step
    val colors = ByteArray(columns * rows)
    for (y in 0 until rows) for (x in 0 until columns) {
        val pixel = pixels[min(height - 1, y * step + step / 2) * width + min(width - 1, x * step + step / 2)]
        val red = pixel shr 16 and 255
        val green = pixel shr 8 and 255
        val blue = pixel and 255
        colors[y * columns + x] = when {
            pixel ushr 24 < 220 -> 0
            red >= 130 && green <= red * .55 && blue <= red * .60 -> 2
            red >= 150 && green >= 75 && green <= red * .86 && blue <= green * .85 && red - green >= 20 -> 1
            else -> 0
        }
    }
    val orange = mutableListOf<IntRect>()
    val red = mutableListOf<IntRect>()
    for (component in pixelComponents(colors, columns, rows, minimumPixels = 12)) {
        val bounds = component.bounds
        val rect = IntRect(bounds.left * step, bounds.top * step,
            min(width, bounds.right * step), min(height, bounds.bottom * step))
        val regionWidth = rect.right - rect.left
        val regionHeight = rect.bottom - rect.top
        val fill = component.pixels.toDouble() / ((bounds.right - bounds.left) * (bounds.bottom - bounds.top))
        if (component.kind == 1 && regionWidth.toDouble() in width * .15..width * .85 &&
            regionHeight.toDouble() in height * .025..height * .25 && fill >= .40) orange += rect
        val panelShape = regionWidth >= width * .40 && regionHeight >= height * .25 && regionHeight <= height * .90
        val resultHeaderShape = regionWidth >= width * .85 && rect.top <= height * .04 &&
            regionHeight.toDouble() in height * .06..height * .24
        if (component.kind == 2 && (panelShape || resultHeaderShape) && fill >= .45) red += rect
    }
    return PacketVisualFrame(width, height, lines, orange, red, toolbarDots(pixels, width, height, lines))
}

private data class PixelComponent(val kind: Int, val bounds: IntRect, val pixels: Int)

/** 四邻接不会把仅对角接触的图案并成一个可点击区域。 */
private fun pixelComponents(mask: ByteArray, width: Int, height: Int, minimumPixels: Int): List<PixelComponent> {
    val queue = IntArray(mask.size)
    val result = mutableListOf<PixelComponent>()
    for (start in mask.indices) {
        val kind = mask[start]
        if (kind.toInt() == 0) continue
        var head = 0
        var tail = 1
        queue[0] = start
        mask[start] = 0
        var left = start % width
        var right = left
        var top = start / width
        var bottom = top
        fun offer(index: Int) {
            if (mask[index] == kind) { mask[index] = 0; queue[tail++] = index }
        }
        while (head < tail) {
            val current = queue[head++]
            val x = current % width
            val y = current / width
            left = min(left, x)
            right = max(right, x)
            top = min(top, y)
            bottom = max(bottom, y)
            if (x > 0) offer(current - 1)
            if (x + 1 < width) offer(current + 1)
            if (y > 0) offer(current - width)
            if (y + 1 < height) offer(current + width)
        }
        if (tail >= minimumPixels) result += PixelComponent(kind.toInt(), IntRect(left, top, right + 1, bottom + 1), tail)
    }
    return result
}

private fun toolbarDots(pixels: IntArray, width: Int, height: Int, lines: List<PacketVisualLine>): List<IntRect> {
    val candidates = lines.filter { line ->
        val rect = line.bounds
        val centerY = (rect.top + rect.bottom) / 2.0
        line.text.isNotBlank() && rect.left >= width * .10 && rect.right <= width * .90 &&
            rect.right - rect.left >= width * .055 && rect.bottom > rect.top &&
            rect.bottom - rect.top <= height * .065 && centerY in height * .025..height * .14
    }
    return candidates.mapNotNull { title ->
        val titleHeight = title.bounds.bottom - title.bounds.top
        val centerY = (title.bounds.top + title.bounds.bottom) / 2.0
        val tolerance = max(titleHeight * .65, height * .012)
        val left = (width * .82).toInt()
        val right = (width * .98).toInt()
        val top = max(0, (centerY - tolerance).toInt())
        val bottom = min(height, ceil(centerY + tolerance).toInt())
        val regionWidth = right - left
        val regionHeight = bottom - top
        if (regionWidth <= 0 || regionHeight <= 0) return@mapNotNull null
        val mask = ByteArray(regionWidth * regionHeight)
        for (y in 0 until regionHeight) for (x in 0 until regionWidth) {
            val pixel = pixels[(y + top) * width + x + left]
            val r = pixel shr 16 and 255
            val g = pixel shr 8 and 255
            val b = pixel and 255
            if (pixel ushr 24 >= 220 && max(r, max(g, b)) <= 115 && max(r, max(g, b)) - min(r, min(g, b)) <= 40)
                mask[y * regionWidth + x] = 1
        }
        val dots = pixelComponents(mask, regionWidth, regionHeight, minimumPixels = 3).filter { part ->
            val rect = part.bounds
            val w = rect.right - rect.left
            val h = rect.bottom - rect.top
            w.toDouble() in width * .003..width * .015 && h.toDouble() in width * .003..width * .015 &&
                w.toDouble() / h in .65..1.50 && part.pixels.toDouble() / (w * h) >= .45 &&
                rect.left > 0 && rect.top > 0 && rect.right < regionWidth && rect.bottom < regionHeight
        }.sortedBy { it.bounds.left }
        if (dots.size != 3) return@mapNotNull null
        val rects = dots.map { it.bounds }
        val sizes = rects.map { max(it.right - it.left, it.bottom - it.top) }
        if (sizes.max() > sizes.min() * 1.5) return@mapNotNull null
        val centersX = rects.map { (it.left + it.right) / 2.0 }
        val centersY = rects.map { (it.top + it.bottom) / 2.0 }
        val firstGap = centersX[1] - centersX[0]
        val secondGap = centersX[2] - centersX[1]
        if (firstGap !in width * .008..width * .035 || secondGap !in width * .008..width * .035 ||
            max(firstGap, secondGap) > min(firstGap, secondGap) * 1.3 ||
            centersY.max() - centersY.min() > sizes.max() * .35 ||
            abs(top + centersY.average() - centerY) > tolerance) return@mapNotNull null
        IntRect(left + rects.minOf { it.left }, top + rects.minOf { it.top },
            left + rects.maxOf { it.right }, top + rects.maxOf { it.bottom })
    }.distinct()
}
