package com.yuyan.imemodule.expression.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable

/** 项目自绘的 AI 斗图提示气泡：圆角矩形加右下方短尾角。 */
class AiDoutuBadgeDrawable(
    context: Context,
    private val fillColor: Int,
    val cornerRadiusDp: Int = 8,
    val tailHeightDp: Int = 5,
    val verticalInsetDp: Int = 6,
) : Drawable() {
    private val density = context.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fillColor }
    private val path = Path()

    override fun draw(canvas: Canvas) {
        val tail = tailHeightDp * density
        val radius = cornerRadiusDp * density
        val verticalInset = verticalInsetDp * density
        val visualTop = bounds.top + verticalInset
        val visualBottom = bounds.bottom - verticalInset
        val bodyBottom = visualBottom - tail
        canvas.drawRoundRect(
            RectF(bounds.left.toFloat(), visualTop, bounds.right.toFloat(), bodyBottom),
            radius,
            radius,
            paint,
        )
        val anchor = bounds.right - 18f * density
        path.reset()
        path.moveTo(anchor - 6f * density, bodyBottom)
        path.lineTo(anchor + 6f * density, bodyBottom)
        path.lineTo(anchor + 3f * density, visualBottom)
        path.close()
        canvas.drawPath(path, paint)
    }

    override fun getPadding(padding: Rect): Boolean {
        padding.set(
            0,
            (verticalInsetDp * density).toInt(),
            0,
            ((verticalInsetDp + tailHeightDp) * density).toInt(),
        )
        return true
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
