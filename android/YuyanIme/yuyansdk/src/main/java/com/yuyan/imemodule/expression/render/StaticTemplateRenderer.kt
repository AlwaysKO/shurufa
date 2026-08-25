package com.yuyan.imemodule.expression.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
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
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = calculated.fontSize
            textAlign = Paint.Align.LEFT
        }
        val canvas = Canvas(output)
        calculated.lines.forEach { line ->
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = layout.strokeWidth.toFloat()
            paint.color = Color.parseColor(layout.strokeColor)
            canvas.drawText(line.text, line.left, line.baseline, paint)

            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor(layout.textColor)
            canvas.drawText(line.text, line.left, line.baseline, paint)
        }
        return output
    }
}
