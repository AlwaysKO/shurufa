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
}

data class RowGeometry(
    val top: Float,
    val keys: List<KeyGeometry>,
) {
    val touchTop: Float get() = keys.first().touchTop
    val touchBottom: Float get() = keys.first().touchBottom
}

data class RowGeometrySpec(
    val top: Float,
    val startX: Float,
    val keyWidths: List<Float>,
    val keyHeight: Float,
    val horizontalGap: Float = 0f,
    val touchLeft: Float = 0f,
    val touchRight: Float = 1f,
)

/**
 * 从视觉规格生成逐键几何。键间触摸边界位于两个视觉键的中线，因此不会产生死区。
 */
fun buildKeyboardGeometry(specs: List<RowGeometrySpec>): List<RowGeometry> {
    require(specs.isNotEmpty())
    specs.forEach { spec ->
        require(spec.keyWidths.isNotEmpty())
        require(spec.keyWidths.all { it > 0f })
        require(spec.keyHeight > 0f && spec.horizontalGap >= 0f)
        require(spec.touchLeft in 0f..1f && spec.touchRight in 0f..1f)
        require(spec.touchLeft < spec.touchRight)
    }

    val visualBottoms = specs.map { it.top + it.keyHeight }
    val rowTouchTops = specs.indices.map { index ->
        if (index == 0) 0f else (visualBottoms[index - 1] + specs[index].top) / 2f
    }
    val rowTouchBottoms = specs.indices.map { index ->
        if (index == specs.lastIndex) 1f else rowTouchTops[index + 1]
    }

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
