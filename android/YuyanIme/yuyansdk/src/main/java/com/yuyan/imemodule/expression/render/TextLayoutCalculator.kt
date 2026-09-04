package com.yuyan.imemodule.expression.render

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import com.yuyan.imemodule.expression.model.ExpressionTextLayout

data class TextLineLayout(
    val text: String,
    val baseline: Float,
    val bounds: RectF,
) {
    val left: Float get() = bounds.left
    val top: Float get() = bounds.top
    val right: Float get() = bounds.right
    val bottom: Float get() = bounds.bottom
}

data class CalculatedTextLayout(
    val fontSize: Float,
    val lines: List<TextLineLayout>,
)

class TextLayoutCalculator {
    fun calculate(
        text: String,
        safeArea: Rect,
        layout: ExpressionTextLayout,
    ): CalculatedTextLayout {
        require(text.isNotEmpty()) { "text must not be empty" }
        require(safeArea.width() > 0 && safeArea.height() > 0) { "safe area must not be empty" }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
            isFakeBoldText = true
        }
        for (size in layout.maxFontSize downTo 1) {
            paint.textSize = size.toFloat()
            val lines = wrap(text, paint, safeArea.width().toFloat(), layout.maxLines)
            val metrics = paint.fontMetrics
            val lineHeight = metrics.descent - metrics.ascent
            if (lines.joinToString("").length == text.length && lineHeight * lines.size <= safeArea.height()) {
                return position(lines, paint, safeArea, layout.alignment)
            }
        }

        error("text cannot fit inside safe area without truncation")
    }

    private fun wrap(text: String, paint: Paint, maxWidth: Float, maxLines: Int): List<String> {
        val lines = mutableListOf<String>()
        var start = 0
        while (start < text.length && lines.size < maxLines) {
            var end = start + 1
            var lastFittingEnd = end
            while (end <= text.length && paint.measureText(text, start, end) <= maxWidth) {
                lastFittingEnd = end
                end += 1
            }
            lines += text.substring(start, lastFittingEnd)
            start = lastFittingEnd
        }
        return lines
    }

    private fun position(
        lines: List<String>,
        paint: Paint,
        safeArea: Rect,
        alignment: String,
    ): CalculatedTextLayout {
        val metrics = paint.fontMetrics
        val lineHeight = metrics.descent - metrics.ascent
        val totalHeight = lineHeight * lines.size
        val firstBaseline = safeArea.top + (safeArea.height() - totalHeight) / 2f - metrics.ascent
        return CalculatedTextLayout(
            fontSize = paint.textSize,
            lines = lines.mapIndexed { index, line ->
                val width = paint.measureText(line)
                val left = when (alignment.lowercase()) {
                    "left", "start" -> safeArea.left.toFloat()
                    "right", "end" -> safeArea.right - width
                    else -> safeArea.left + (safeArea.width() - width) / 2f
                }
                val baseline = firstBaseline + index * lineHeight
                TextLineLayout(
                    text = line,
                    baseline = baseline,
                    bounds = RectF(left, baseline + metrics.ascent, left + width, baseline + metrics.descent),
                )
            },
        )
    }
}
