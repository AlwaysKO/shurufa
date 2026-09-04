package com.yuyan.imemodule.expression.render

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Paint
import com.yuyan.imemodule.expression.model.ExpressionTextLayout
import com.yuyan.imemodule.expression.model.ExpressionTextSafeArea
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TextLayoutCalculatorTest {
    private val layout = ExpressionTextLayout(
        minFontSize = 18,
        maxFontSize = 48,
        textColor = "#ffffff",
        strokeColor = "#000000",
        strokeWidth = 3,
        alignment = "center",
        maxLines = 2,
    )

    @Test
    fun `二到八字的字号随文字增长递减`() {
        val calculator = TextLayoutCalculator()
        val sizes = (2..8).map { length ->
            calculator.calculate("字".repeat(length), Rect(0, 0, 220, 100), layout).fontSize
        }

        assertTrue(sizes.zipWithNext().all { (left, right) -> left >= right })
        assertTrue(sizes.first() > sizes.last())
    }

    @Test
    fun `长文本换行后不越过安全区`() {
        val safeArea = Rect(20, 10, 180, 100)

        val result = TextLayoutCalculator().calculate("长文本需要安全换行", safeArea, layout)

        assertTrue(result.lines.size in 1..layout.maxLines)
        assertTrue(result.lines.all { line ->
            line.left >= safeArea.left &&
                line.right <= safeArea.right &&
                line.top >= safeArea.top &&
                line.bottom <= safeArea.bottom
        })
    }

    @Test
    fun `最长查询在最小字号放不下时继续缩放且不截字`() {
        val query = "生".repeat(100)
        val safeArea = Rect(0, 0, 80, 40)

        val result = TextLayoutCalculator().calculate(query, safeArea, layout)

        assertEquals(query, result.lines.joinToString("") { it.text })
        assertTrue(result.fontSize < layout.minFontSize)
        assertTrue(result.lines.size <= layout.maxLines)
        assertTrue(result.lines.all { line ->
            line.left >= safeArea.left &&
                line.right <= safeArea.right &&
                line.top >= safeArea.top &&
                line.bottom <= safeArea.bottom
        })
    }

    @Test
    fun `静态模板绘制白字黑描边`() {
        val source = Bitmap.createBitmap(240, 120, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.TRANSPARENT)
        }
        val result = StaticTemplateRenderer().render(
            source = source,
            text = "OK",
            safeArea = ExpressionTextSafeArea(20, 10, 200, 100),
            layout = layout,
        )
        val pixels = IntArray(result.width * result.height)
        result.getPixels(pixels, 0, result.width, 0, 0, result.width, result.height)

        assertTrue(pixels.any { Color.alpha(it) > 0 && Color.red(it) > 220 && Color.green(it) > 220 })
        assertTrue(pixels.any { Color.alpha(it) > 0 && Color.red(it) < 30 && Color.green(it) < 30 })
    }

    @Test
    fun `候选叠字使用粗体且描边随大字号增强`() {
        val fill = expressionTextPaint(layout, fontSize = 84f, style = Paint.Style.FILL)
        val stroke = expressionTextPaint(layout, fontSize = 84f, style = Paint.Style.STROKE)

        assertTrue(fill.isFakeBoldText)
        assertTrue(stroke.isFakeBoldText)
        assertTrue(stroke.strokeWidth >= 6f)
    }
}
