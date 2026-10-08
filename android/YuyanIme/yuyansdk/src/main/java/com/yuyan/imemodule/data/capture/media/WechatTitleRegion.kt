package com.yuyan.imemodule.data.capture.media

import android.graphics.Bitmap
import android.graphics.Color
import com.yuyan.imemodule.data.capture.ui.IntRect
import kotlin.math.abs
import kotlin.math.roundToInt

/** 只用于状态栏之后已知的微信 44dp 标题带；不处理正文、旧版含状态栏回退或整图。 */
internal fun prepareWechatTitleHeader(header: Bitmap): Bitmap {
    val output = header.copy(Bitmap.Config.ARGB_8888, true)
    val width = header.width
    val height = header.height
    if (height < 8 || width < height * 5) return output
    val pixels = IntArray(width * height)
    header.getPixels(pixels, 0, width, 0, 0, width, height)
    val background = pixels[width / 2]
    // 彩色背景不是已验证的标准标题栏，不猜其前景颜色。
    if (chroma(background) > 30) return output
    val ink = BooleanArray(width)
    val colored = BooleanArray(width)
    val top = (height * .12).toInt()
    val bottom = (height * .88).toInt()
    for (x in 0 until width) {
        var colorCount = 0
        for (y in top until bottom) {
            val pixel = pixels[y * width + x]
            if (colorDistance(pixel, background) > 40) ink[x] = true
            if (chroma(pixel) > 60 && maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) > 120) colorCount++
        }
        colored[x] = colorCount >= maxOf(2, (height * .02).toInt())
    }
    fun mask(left: Int, right: Int) {
        for (y in 0 until height) java.util.Arrays.fill(pixels, y * width + left, y * width + right, background)
    }
    val edge = (height * 1.05).toInt()
    val gap = maxOf(1, (height * .04).toInt())
    // 标题笔画跨过候选边界时不截字；保留原输入供选择器拒绝噪声。
    if ((edge-gap..edge+gap).none { ink[it] }) mask(0, edge)
    if ((width-edge-gap..width-edge+gap).none { ink[it] }) mask(width-edge, width)
    val coloredColumns = (edge until width-edge).count { colored[it] }
    val neutralColumns = (edge until width-edge).count { ink[it] && !colored[it] }
    // 彩色文字为主的主题/昵称未验证，不把它当成一串图标擦掉。
    if (coloredColumns * 2 < neutralColumns) {
        var x = edge
        while (x < width-edge) {
            if (!colored[x]) { x++; continue }
            val start = x
            var end = x
            while (++x < width-edge) {
                if (colored[x]) end = x
                else if (x-end > gap) break
            }
            if (end-start+1 < maxOf(4, (height * .10).toInt())) continue
            val expansion = maxOf(1, (height * .08).toInt())
            var left = start
            var right = end
            while (left > maxOf(edge,start-expansion) && ink[left]) left--
            while (right < minOf(width-edge-1,end+expansion) && ink[right]) right++
            // 只在两边都有实际空白时去掉独立图标及黑轮廓，不越过相邻汉字。
            if (!ink[left] && !ink[right]) mask(left,right+1)
        }
    }
    output.setPixels(pixels, 0, width, 0, 0, width, height)
    return output
}

private fun chroma(color: Int): Int = maxOf(Color.red(color), Color.green(color), Color.blue(color)) -
    minOf(Color.red(color), Color.green(color), Color.blue(color))
private fun colorDistance(a: Int, b: Int): Int = maxOf(abs(Color.red(a)-Color.red(b)),
    abs(Color.green(a)-Color.green(b)), abs(Color.blue(a)-Color.blue(b)))

/** 显示去掉装饰，身份仍保留原始昵称字形/Emoji；不让 OCR 行框和群人数改变键。 */
internal fun wechatTitleEvidenceBounds(header: Bitmap, title: OcrTextLine): OcrTextLine? {
    val width = header.width
    val height = header.height
    if (height < 8 || width < height * 5) return null
    val title = withoutUnsupportedWechatTitlePrefix(header, title)?.let {
        withoutSeparatedWechatTitleControl(header, it)
    } ?: return null
    val left = (height * 1.05).toInt()
    val top = (height * .12).toInt()
    val bottom = (height * .88).toInt()
    if (title.top < top || title.bottom > bottom) return null
    val background = header.getPixel(width / 2, 0)
    fun blankColumn(x: Int): Boolean = (top until bottom).all {
        colorDistance(header.getPixel(x, it), background) <= 40
    }
    if (!blankColumn(left)) return null // 不用裁掉半个字的证据确认身份。
    var right = width - left
    val suffix = Regex("[（(]\\s*\\d+\\s*[）)](?:\\s*[A-Za-z0-9]{1,2})?$")
    if (suffix.containsMatchIn(title.text.trim())) {
        val symbols = title.symbols
        val compact = symbols.joinToString("") { it.text.filterNot(Char::isWhitespace) }
        val match = suffix.find(compact) ?: return null
        var offset = 0
        val opening = symbols.firstOrNull {
            val begins = offset == match.range.first
            offset += it.text.count { char -> !char.isWhitespace() }
            begins
        } ?: return null
        // 必须是左括号自己的框，不能用整个元素/整行框猜切界。
        if (opening.text.trim() !in setOf("(", "（") || opening.left <= left || opening.right >= right) return null
        right = opening.left
        val limit = maxOf(left, right - maxOf(1, (height * .10).toInt()))
        while (right > limit && !blankColumn(right)) right--
        if (!blankColumn(right)) return null
    } else if (!blankColumn(right)) return null
    return title.copy(left = left, top = top, right = right, bottom = bottom)
}

/** 仅移除有完整字符框且原图对应区域全空白的前置竖线，真实昵称符号保留。 */
private fun withoutUnsupportedWechatTitlePrefix(header: Bitmap, title: OcrTextLine): OcrTextLine? {
    var result = title
    while (result.text.trimStart().firstOrNull() in setOf('|', '｜', '¦')) {
        if (result.symbols.joinToString("") { it.text.filterNot(Char::isWhitespace) } !=
            result.text.filterNot(Char::isWhitespace)) return null
        val symbol = result.symbols.firstOrNull() ?: return null
        if (symbol.text.trim() !in setOf("|", "｜", "¦") || symbol.left < 0 || symbol.top < 0 ||
            symbol.right > header.width || symbol.bottom > header.height ||
            symbol.right <= symbol.left || symbol.bottom <= symbol.top) return null
        val background = header.getPixel(header.width / 2, 0)
        for (y in symbol.top until symbol.bottom) for (x in symbol.left until symbol.right) {
            if (colorDistance(header.getPixel(x, y), background) > 40) return result
        }
        result = result.copy(text = result.text.trimStart().removePrefix(symbol.text.trim()).trimStart(),
            symbols = result.symbols.drop(1))
    }
    return result
}

/** 人数后的短尾字须有原始像素证据；深色主题的小控件可能与文字同亮度。 */
private fun withoutSeparatedWechatTitleControl(header: Bitmap, title: OcrTextLine): OcrTextLine? {
    val match = Regex("[（(]\\s*\\d+\\s*[）)]").findAll(title.text).lastOrNull() ?: return title
    val tail = title.text.substring(match.range.last + 1).trim()
    if (tail.isEmpty() || tail.length > 2) return title
    val compact = title.symbols.joinToString("") { it.text.filterNot(Char::isWhitespace) }
    if (compact != title.text.filterNot(Char::isWhitespace)) return null
    val trailer = title.symbols.takeLastWhile { symbol ->
        symbol.text.isNotBlank() && symbol.text.none { it == ')' || it == '）' } &&
            tail.contains(symbol.text.trim())
    }
    if (trailer.isEmpty() || trailer.joinToString("") { it.text.trim() } != tail) return null
    val preceding = title.symbols.dropLast(trailer.size).lastOrNull() ?: return null
    val background = header.getPixel(header.width / 2, 0)
    // OCR 的相邻符号框会重叠；只用原图实际空白列判定控件分隔。
    var gap = 0
    var longestGap = 0
    val scanTop = minOf(preceding.top, trailer.minOf { it.top }).coerceAtLeast(0)
    val scanBottom = maxOf(preceding.bottom, trailer.maxOf { it.bottom }).coerceAtMost(header.height)
    val scanLeft = ((preceding.left + preceding.right) / 2).coerceAtLeast(0)
    val scanRight = ((trailer.first().left + trailer.first().right) / 2).coerceAtMost(header.width)
    for (x in scanLeft until scanRight) {
        val blank = (scanTop until scanBottom).all { colorDistance(header.getPixel(x, it), background) <= 20 }
        gap = if (blank) gap + 1 else 0
        longestGap = maxOf(longestGap, gap)
    }
    if (longestGap < header.height * .08) return null
    fun contrast(symbols: List<OcrTextSymbol>): Int = symbols.maxOfOrNull { symbol ->
        var peak = 0
        for (y in symbol.top.coerceAtLeast(0) until symbol.bottom.coerceAtMost(header.height)) {
            for (x in symbol.left.coerceAtLeast(0) until symbol.right.coerceAtMost(header.width)) {
                val pixel = header.getPixel(x, y)
                if (chroma(pixel) > 30) return -1 // 彩色昵称/主题不猜控件。
                peak = maxOf(peak, colorDistance(pixel, background))
            }
        }
        peak
    } ?: 0
    val textContrast = contrast(listOf(preceding))
    val controlContrast = contrast(trailer)
    if (textContrast < 80 || controlContrast < 20) return null
    // 按实际墨迹而不是OCR外框比较；小图标的OCR框也可能与文字一样高。
    fun inkBounds(symbols: List<OcrTextSymbol>): IntRect? {
        var left = header.width; var right = 0
        var top = header.height; var bottom = 0
        for (symbol in symbols) {
            for (y in symbol.top.coerceAtLeast(0) until symbol.bottom.coerceAtMost(header.height)) {
                for (x in symbol.left.coerceAtLeast(0) until symbol.right.coerceAtMost(header.width)) {
                    if (colorDistance(header.getPixel(x, y), background) <= 40) continue
                    left = minOf(left, x); right = maxOf(right, x + 1)
                    top = minOf(top, y); bottom = maxOf(bottom, y + 1)
                }
            }
        }
        return if (right > left && bottom > top) IntRect(left, top, right, bottom) else null
    }
    val textBounds = inkBounds(listOf(preceding)) ?: return null
    val textHeight = textBounds.bottom - textBounds.top
    val controlBounds = inkBounds(trailer) ?: return null
    val controlWidth = controlBounds.right - controlBounds.left
    val controlHeight = controlBounds.bottom - controlBounds.top
    val smallSeparatedControl = longestGap >= header.height * .12 &&
        controlWidth <= textHeight * .90 && controlHeight <= textHeight * .85
    // 大小、颜色都不能独立证明是图标；未知尾字保留为原始观测，不提供身份确认像素。
    if (!smallSeparatedControl || !matchesWechatMutedBell(header, controlBounds, background)) return null
    return title.copy(text = title.text.substring(0, match.range.last + 1).trim(),
        right = preceding.right, symbols = title.symbols.dropLast(trailer.size))
}

/** 已观察到的静音铃铛：贯穿斜线、两侧轮廓、底边和铃舌；换形状时保守待确认。 */
private fun matchesWechatMutedBell(header: Bitmap, bounds: IntRect, background: Int): Boolean {
    val width = bounds.right - bounds.left
    val height = bounds.bottom - bounds.top
    if (width < 12 || height < 12) return false
    val radius = maxOf(1, (minOf(width, height) * .035).roundToInt())
    fun inkAt(xRatio: Double, yRatio: Double): Boolean {
        val x = bounds.left + ((width - 1) * xRatio).roundToInt()
        val y = bounds.top + ((height - 1) * yRatio).roundToInt()
        for (py in maxOf(bounds.top, y - radius)..minOf(bounds.bottom - 1, y + radius)) {
            for (px in maxOf(bounds.left, x - radius)..minOf(bounds.right - 1, x + radius)) {
                if (colorDistance(header.getPixel(px, py), background) > 40) return true
            }
        }
        return false
    }
    if ((2..9).any { !inkAt(it / 10.0, .1 + .9 * it / 10.0) }) return false
    val outline = listOf(.47 to .07, .17 to .40, .72 to .40, .17 to .60, .72 to .60,
        .20 to .87, .40 to .87, .60 to .87, .46 to .96)
    return outline.all { (x, y) -> inkAt(x, y) } && !inkAt(.42, .27) && !inkAt(.35, .64)
}

internal fun wechatNicknamePixelSignature(header: Bitmap, bounds: OcrTextLine): String? {
    if (header.isRecycled || bounds.left < 0 || bounds.top < 0 || bounds.right > header.width ||
        bounds.bottom > header.height || bounds.right <= bounds.left || bounds.bottom <= bounds.top) return null
    val background = header.getPixel(header.width / 2, 0)
    val row = IntArray(bounds.right - bounds.left)
    var left = bounds.right; var right = bounds.left
    var top = bounds.bottom; var bottom = bounds.top
    for (y in bounds.top until bounds.bottom) {
        header.getPixels(row,0,row.size,bounds.left,y,row.size,1)
        for (x in row.indices) if (colorDistance(row[x],background) > 40) {
            left = minOf(left,bounds.left+x); right = maxOf(right,bounds.left+x+1)
            top = minOf(top,y); bottom = maxOf(bottom,y+1)
        }
    }
    if (right <= left || bottom <= top) return null
    // 新的原始标题带不二值化：同轮廓异色 Emoji 也必须保持不同身份。
    // 去掉外围空白后逐行计算精确 RGBA，不持有整图或额外的全图字节数组。
    return exactPixelHash(header, com.yuyan.imemodule.data.capture.ui.IntRect(left,top,right,bottom))
}

internal suspend fun recognizePreparedWechatTitleHeader(
    header: Bitmap,
    recognize: suspend (Bitmap) -> List<OcrTextLine>,
): List<OcrTextLine> {
    // 仅放大小标题带；昵称证据和省略号检查始终使用原始像素。
    val scale = if (header.width <= 1600 && header.height <= 256) 2 else 1
    if (scale == 1) return awaitTitleOcrCompletion { recognize(header) }
    val enlarged = Bitmap.createScaledBitmap(header, header.width * scale, header.height * scale, true)
    try {
        return awaitTitleOcrCompletion { recognize(enlarged) }.map { line ->
            line.copy(left = line.left / scale, top = line.top / scale,
                right = (line.right + scale - 1) / scale, bottom = (line.bottom + scale - 1) / scale,
                symbols = line.symbols.map { symbol ->
                    symbol.copy(left = symbol.left / scale, top = symbol.top / scale,
                        right = (symbol.right + scale - 1) / scale, bottom = (symbol.bottom + scale - 1) / scale)
                })
        }
    } finally { enlarged.recycle() }
}
