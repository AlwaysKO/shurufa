package com.yuyan.imemodule.expression.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import com.yuyan.imemodule.expression.model.ExpressionTextLayout
import com.yuyan.imemodule.expression.model.ExpressionTextSafeArea

class StaticTemplateRenderer(
    private val calculator: TextLayoutCalculator = TextLayoutCalculator(),
) {
    fun render(
        source: Bitmap,
        text: String,
        safeArea: ExpressionTextSafeArea,
        layout: ExpressionTextLayout,
    ): Bitmap {
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val calculated = calculator.calculate(
            text,
            Rect(safeArea.x, safeArea.y, safeArea.x + safeArea.width, safeArea.y + safeArea.height),
            layout,
        )
        val strokePaint = expressionTextPaint(layout, calculated.fontSize, Paint.Style.STROKE)
        val fillPaint = expressionTextPaint(layout, calculated.fontSize, Paint.Style.FILL)
        val canvas = Canvas(output)
        calculated.lines.forEach { line ->
            canvas.drawText(line.text, line.left, line.baseline, strokePaint)
            canvas.drawText(line.text, line.left, line.baseline, fillPaint)
        }
        return output
    }
}

internal fun expressionTextPaint(
    layout: ExpressionTextLayout,
    fontSize: Float,
    style: Paint.Style,
): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    textSize = fontSize
    textAlign = Paint.Align.LEFT
    typeface = Typeface.DEFAULT_BOLD
    isFakeBoldText = true
    this.style = style
    strokeJoin = Paint.Join.ROUND
    strokeCap = Paint.Cap.ROUND
    strokeWidth = if (style == Paint.Style.STROKE) {
        maxOf(layout.strokeWidth.toFloat(), fontSize * 0.075f)
    } else {
        0f
    }
    color = Color.parseColor(
        if (style == Paint.Style.STROKE) layout.strokeColor else layout.textColor,
    )
}
