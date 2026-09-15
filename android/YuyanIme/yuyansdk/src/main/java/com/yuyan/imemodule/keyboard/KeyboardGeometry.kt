package com.yuyan.imemodule.keyboard

/** 归一化键盘坐标中的单键视觉矩形与连续触摸矩形。 */
data class KeyGeometry(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val touchLeft: Float,
    val touchTop: Float,
    val touchRight: Float,
    val touchBottom: Float,
) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height

    init {
        require(width > 0f && height > 0f)
        require(left >= 0f && top >= 0f)
        require(right <= 1f && bottom <= 1f)
        require(touchLeft in 0f..1f && touchRight in 0f..1f)
        require(touchTop in 0f..1f && touchBottom in 0f..1f)
        require(touchLeft < touchRight && touchTop < touchBottom)
        require(left + width / 2f in touchLeft..touchRight)
        require(top + height / 2f in touchTop..touchBottom)
    }
}

class RowGeometry(
    val top: Float,
    keys: List<KeyGeometry>,
) {
    private val keyValues = keys.toList()
    val keys: List<KeyGeometry> get() = keyValues.toList()
    val touchTop: Float get() = keyValues.first().touchTop
    val touchBottom: Float get() = keyValues.first().touchBottom

    init {
        require(top in 0f..1f)
        require(keyValues.isNotEmpty())
        require(keyValues.all { it.top == top })
        require(keyValues.all { it.touchTop == touchTop && it.touchBottom == touchBottom })
        require(keyValues.zipWithNext().all { (left, right) ->
            left.left < right.left && left.touchRight == right.touchLeft
        })
    }
}

class RowGeometrySpec(
    val top: Float,
    val startX: Float,
    keyWidths: List<Float>,
    val keyHeight: Float,
    val horizontalGap: Float = 0f,
    val touchLeft: Float = 0f,
    val touchRight: Float = 1f,
) {
    private val keyWidthValues = keyWidths.toList()
    val keyWidths: List<Float> get() = keyWidthValues.toList()

    init {
        require(top >= 0f && keyHeight > 0f && top + keyHeight <= 1f)
        require(startX in 0f..1f)
        require(keyWidthValues.isNotEmpty())
        require(keyWidthValues.all { it > 0f })
        require(horizontalGap >= 0f)
        require(touchLeft in 0f..1f && touchRight in 0f..1f)
        require(touchLeft < touchRight)
    }
}

/**
 * 从视觉规格生成逐键几何。键间触摸边界位于两个视觉键的中线，因此不会产生死区。
 */
fun buildKeyboardGeometry(specs: List<RowGeometrySpec>): List<RowGeometry> {
    require(specs.isNotEmpty())
    require(specs.zipWithNext().all { (upper, lower) -> upper.top < lower.top })

    val visualBottoms = specs.map { it.top + it.keyHeight }
    val visualCenters = specs.mapIndexed { index, spec -> (spec.top + visualBottoms[index]) / 2f }
    require(visualCenters.zipWithNext().all { (upper, lower) -> upper < lower })
    val rowTouchTops = specs.indices.map { index ->
        if (index == 0) 0f else (visualBottoms[index - 1] + specs[index].top) / 2f
    }
    val rowTouchBottoms = specs.indices.map { index ->
        if (index == specs.lastIndex) 1f else rowTouchTops[index + 1]
    }
    require(rowTouchTops.zip(rowTouchBottoms).all { (top, bottom) ->
        top in 0f..1f && bottom in 0f..1f && top < bottom
    })

    return specs.mapIndexed { rowIndex, spec ->
        var nextLeft = spec.startX
        val visualKeys = spec.keyWidths.map { width ->
            val left = nextLeft
            nextLeft += width + spec.horizontalGap
            left to width
        }
        require(visualKeys.first().first >= spec.touchLeft)
        require(visualKeys.last().let { (left, width) -> left + width } <= spec.touchRight)

        val internalTouchBoundaries = visualKeys.zipWithNext { left, right ->
            ((left.first + left.second) + right.first) / 2f
        }
        val keys = visualKeys.mapIndexed { keyIndex, (left, width) ->
            KeyGeometry(
                left = left,
                top = spec.top,
                width = width,
                height = spec.keyHeight,
                touchLeft = internalTouchBoundaries.getOrElse(keyIndex - 1) { spec.touchLeft },
                touchTop = rowTouchTops[rowIndex],
                touchRight = internalTouchBoundaries.getOrElse(keyIndex) { spec.touchRight },
                touchBottom = rowTouchBottoms[rowIndex],
            )
        }
        RowGeometry(top = spec.top, keys = keys)
    }
}
