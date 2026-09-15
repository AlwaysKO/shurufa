package com.yuyan.imemodule.keyboard.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.yuyan.imemodule.keyboard.KeyboardSurfaceLayoutFamily
import com.yuyan.imemodule.keyboard.KeyboardSurfaceTheme
import kotlin.math.min

/** 使用项目自有绘制代码生成主题缩略图，不依赖第三方预览图片。 */
class KeyboardThemePreviewView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var theme: KeyboardSurfaceTheme? = null
    private var qwerty = false

    fun bind(theme: KeyboardSurfaceTheme, qwerty: Boolean) {
        this.theme = theme
        this.qwerty = qwerty
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val spec = theme ?: return
        canvas.drawColor(spec.keyboardColor)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        drawToolbar(canvas, spec, w, h * 0.16f)
        if (qwerty) drawQwerty(canvas, spec, w, h) else drawT9(canvas, spec, w, h)
    }

    private fun drawToolbar(canvas: Canvas, spec: KeyboardSurfaceTheme, width: Float, height: Float) {
        paint.color = spec.textColor
        val radius = min(width, this.height.toFloat()) * 0.022f
        val count = when (spec.layoutFamily) {
            KeyboardSurfaceLayoutFamily.SOGOU -> 7
            KeyboardSurfaceLayoutFamily.WECHAT -> 3
            KeyboardSurfaceLayoutFamily.HUAWEI -> 4
        }
        repeat(count) { index ->
            val x = if (count == 3 && index > 0) {
                width - radius * (5.5f - (index - 1) * 3f)
            } else {
                radius * 2f + index * ((width - radius * 4f) / (count - 1).coerceAtLeast(1))
            }
            canvas.drawCircle(x, height * 0.52f, radius, paint)
        }
    }

    private fun drawT9(canvas: Canvas, spec: KeyboardSurfaceTheme, width: Float, height: Float) {
        val top = height * 0.18f
        val bottom = height * 0.98f
        val gap = width * 0.012f
        val rowGap = height * 0.018f
        val leftWidth = width * 0.16f
        val rightWidth = width * 0.16f
        val centerWidth = width - leftWidth - rightWidth - gap * 6f
        val mainWidth = centerWidth / 3f
        val rowHeight = (bottom - top - rowGap * 3f) / 4f
        paint.style = Paint.Style.FILL
        repeat(3) { row ->
            repeat(3) { col ->
                drawKey(canvas, spec.keyColor, leftWidth + gap * (col + 2) + mainWidth * col, top + row * (rowHeight + rowGap), mainWidth, rowHeight, gap)
            }
        }
        drawKey(canvas, spec.keyColor, gap, top, leftWidth, rowHeight * 3f + rowGap * 2f, gap)
        repeat(3) { row ->
            val color = if (spec.layoutFamily == KeyboardSurfaceLayoutFamily.WECHAT && row >= 1) spec.accentColor else spec.functionKeyColor
            drawKey(canvas, color, width - rightWidth - gap, top + row * (rowHeight + rowGap), rightWidth, rowHeight, gap)
        }
        val bottomWidths = floatArrayOf(0.16f, 0.16f, 0.32f, 0.16f, 0.16f)
        var x = gap
        bottomWidths.forEachIndexed { index, ratio ->
            val keyWidth = width * ratio - gap
            val color = if (index == bottomWidths.lastIndex) spec.accentColor else if (index < 2 || index == 3) spec.functionKeyColor else spec.keyColor
            drawKey(canvas, color, x, top + 3f * (rowHeight + rowGap), keyWidth, rowHeight, gap)
            x += width * ratio
        }
    }

    private fun drawQwerty(canvas: Canvas, spec: KeyboardSurfaceTheme, width: Float, height: Float) {
        val top = height * 0.18f
        val gap = width * 0.009f
        val rowGap = height * 0.018f
        val rowHeight = (height * 0.80f - rowGap * 3f) / 4f
        val counts = intArrayOf(10, 9, 9)
        counts.forEachIndexed { row, count ->
            val side = if (row == 1) width * 0.05f else gap
            val keyWidth = (width - side * 2f - gap * (count - 1)) / count
            repeat(count) { col ->
                val function = row == 2 && (col == 0 || col == count - 1)
                drawKey(canvas, if (function) spec.functionKeyColor else spec.keyColor, side + col * (keyWidth + gap), top + row * (rowHeight + rowGap), keyWidth, rowHeight, gap)
            }
        }
        val ratios = if (spec.usesCompactFiveKeyQwertyBottomRow) {
            floatArrayOf(0.19f, 0.10f, 0.37f, 0.15f, 0.19f)
        } else {
            floatArrayOf(0.14f, 0.115f, 0.09f, 0.25f, 0.09f, 0.115f, 0.14f)
        }
        var x = gap
        ratios.forEachIndexed { index, ratio ->
            val keyWidth = width * ratio - gap
            val color = when {
                index == ratios.lastIndex -> spec.accentColor
                index == ratios.size / 2 -> spec.keyColor
                else -> spec.functionKeyColor
            }
            drawKey(canvas, color, x, top + 3f * (rowHeight + rowGap), keyWidth, rowHeight, gap)
            x += width * ratio
        }
    }

    private fun drawKey(canvas: Canvas, color: Int, x: Float, y: Float, width: Float, height: Float, radius: Float) {
        paint.color = color
        canvas.drawRoundRect(RectF(x, y, x + width, y + height), radius, radius, paint)
    }
}
