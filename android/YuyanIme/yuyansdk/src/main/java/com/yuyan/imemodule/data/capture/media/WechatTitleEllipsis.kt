package com.yuyan.imemodule.data.capture.media

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs

/** 只恢复 OCR 标点框内有三点像素证据的省略号，不全局替换小数/英文中的句点。 */
internal fun restoreWechatTitleEllipsis(header: Bitmap, title: OcrTextLine): OcrTextLine {
    var cursor = 0
    val replacements = mutableListOf<Pair<Int, Int>>()
    val symbols = title.symbols.map { symbol ->
        val at = title.text.indexOf(symbol.text, cursor)
        if (at < 0 || symbol.text.isEmpty()) return title
        cursor = at + symbol.text.length
        if ((title.text.take(at) + title.text.drop(cursor)).any { it.isLetterOrDigit() } && symbol.text in setOf(".", "。", "..") &&
            hasThreeBaselineDots(header, symbol)) {
            replacements += at to cursor
            symbol.copy(text = "…")
        } else symbol
    }
    if (replacements.isEmpty()) return title
    val text = StringBuilder(title.text)
    for ((start,end) in replacements.asReversed()) text.replace(start,end,"…")
    return title.copy(text=text.toString(),symbols=symbols)
}

private fun hasThreeBaselineDots(header: Bitmap, symbol: OcrTextSymbol): Boolean {
    val width=symbol.right-symbol.left
    val height=symbol.bottom-symbol.top
    if (width < 6 || height < 12 || width > height*2 || symbol.left < 0 || symbol.top < 0 ||
        symbol.right > header.width || symbol.bottom > header.height) return false
    fun brightness(pixel:Int)=(Color.red(pixel)*299+Color.green(pixel)*587+Color.blue(pixel)*114)/1000
    val background=brightness(header.getPixel(0,0))
    val pixels=IntArray(width*height)
    header.getPixels(pixels,0,width,symbol.left,symbol.top,width,height)
    val ink=BooleanArray(pixels.size) { abs(brightness(pixels[it])-background)>100 }
    data class Dot(val left:Int,val top:Int,val right:Int,val bottom:Int) {
        val width get()=right-left
        val height get()=bottom-top
        val center get()=(left+right)/2.0
    }
    val dots=mutableListOf<Dot>()
    val queue=IntArray(pixels.size)
    for (seed in ink.indices) {
        if (!ink[seed]) continue
        var read=0;var write=1;queue[0]=seed;ink[seed]=false
        var x0=width;var y0=height;var x1=0;var y1=0
        while(read<write) {
            val at=queue[read++];val x=at%width;val y=at/width
            x0=minOf(x0,x);y0=minOf(y0,y);x1=maxOf(x1,x+1);y1=maxOf(y1,y+1)
            fun visit(next:Int) { if(ink[next]) {ink[next]=false;queue[write++]=next} }
            if(x>0) visit(at-1)
            if(x+1<width) visit(at+1)
            if(y>0) visit(at-width)
            if(y+1<height) visit(at+width)
        }
        val dot=Dot(x0,y0,x1,y1)
        // 不把字符框边缘裁下来的汉字碎片当圆点；三个点须小而密实、位于基线附近。
        if (x0>0 && x1<width && y0>0 && y1<height &&
            dot.width in maxOf(2,(height*.06).toInt())..maxOf(3,(height*.25).toInt()) &&
            dot.height in maxOf(2,(height*.06).toInt())..maxOf(3,(height*.25).toInt()) &&
            dot.width.toDouble()/dot.height in .5..1.8 && write >= dot.width*dot.height*.45 &&
            y0 >= height*.5 && y1 >= height*.65) dots += dot
    }
    if(dots.size!=3) return false
    val ordered=dots.sortedBy { it.left }
    val tolerance=maxOf(2,(height*.04).toInt())
    if(ordered.maxOf{it.top}-ordered.minOf{it.top}>tolerance ||
        ordered.maxOf{it.height}-ordered.minOf{it.height}>tolerance ||
        ordered.maxOf{it.width}-ordered.minOf{it.width}>tolerance) return false
    val first=ordered[1].center-ordered[0].center
    val second=ordered[2].center-ordered[1].center
    val dotWidth=ordered.map{it.width}.average()
    return abs(first-second)<=tolerance && first in dotWidth*1.15..dotWidth*2.7 && second in dotWidth*1.15..dotWidth*2.7
}
