package com.yuyan.imemodule.keyboard

import android.view.KeyEvent
import com.yuyan.imemodule.manager.InputModeSwitcher

/**
 * 项目可执行的辅助键盘规格。
 *
 * 笔画与数字键盘只复用 APK 配置中公开可观察的九键尺寸；手写键盘复用其区域尺寸。
 * 文字编辑键盘没有对应的 APK 键盘配置，因此保留项目键位语义并使用项目自有几何。
 */
object SogouAuxiliaryLayouts {
    data class KeyPosition(val row: Int, val column: Int)
    data class LabelOverride(val main: String, val secondary: String = "")

    data class NormalizedRect(
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float,
    ) {
        val right: Float get() = left + width
        val bottom: Float get() = top + height

        init {
            require(left >= 0f && top >= 0f && width >= 0f && height >= 0f)
            require(right <= 1f && bottom <= 1f)
        }
    }

    class LayoutSpec(
        codeRows: List<List<Int>>,
        visualRows: List<List<KeyGeometry>>,
        val drawingArea: NormalizedRect = NormalizedRect(0f, 0f, 0f, 0f),
        labelOverrides: Map<KeyPosition, LabelOverride> = emptyMap(),
    ) {
        private val codeRowValues = codeRows.map { it.toList() }
        private val visualRowValues = visualRows.map { it.toList() }
        private val labelOverrideValues = labelOverrides.toMap()
        val codeRows: List<List<Int>> get() = codeRowValues.map { it.toList() }
        val visualRows: List<List<KeyGeometry>> get() = visualRowValues.map { it.toList() }

        init {
            require(codeRowValues.isNotEmpty())
            require(codeRowValues.map { it.size } == visualRowValues.map { it.size })
            require(visualRowValues.flatten().all { it.right <= 1f && it.bottom <= 1f })
            require(labelOverrideValues.keys.all { position ->
                position.row in codeRowValues.indices && position.column in codeRowValues[position.row].indices
            })
        }

        fun labelOverride(row: Int, column: Int): LabelOverride? = labelOverrideValues[KeyPosition(row, column)]
    }

    val stroke: LayoutSpec = t9StyleSpec(
        codeRows = listOf(
            listOf(
                InputModeSwitcher.USER_KEYCODE_LEFT_SYMBOL,
                KeyEvent.KEYCODE_H,
                KeyEvent.KEYCODE_S,
                KeyEvent.KEYCODE_P,
                KeyEvent.KEYCODE_DEL,
            ),
            listOf(KeyEvent.KEYCODE_N, KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_CLEAR),
            listOf(KeyEvent.KEYCODE_APOSTROPHE, 0, 0, KeyEvent.KEYCODE_0),
            listOf(
                InputModeSwitcher.USER_KEYCODE_SYMBOL,
                InputModeSwitcher.USER_KEYCODE_NUMBER,
                KeyEvent.KEYCODE_SPACE,
                InputModeSwitcher.USER_KEYCODE_LANG,
                KeyEvent.KEYCODE_ENTER,
            ),
        ),
        labelOverrides = mapOf(
            KeyPosition(row = 2, column = 1) to LabelOverride(main = ":", secondary = "8"),
            KeyPosition(row = 2, column = 2) to LabelOverride(main = ";", secondary = "9"),
        ),
    )

    val number: LayoutSpec = t9StyleSpec(
        codeRows = listOf(
            listOf(
                InputModeSwitcher.USER_KEYCODE_LEFT_SYMBOL,
                KeyEvent.KEYCODE_1,
                KeyEvent.KEYCODE_2,
                KeyEvent.KEYCODE_3,
                KeyEvent.KEYCODE_DEL,
            ),
            listOf(KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_6, InputModeSwitcher.USER_KEYCODE_LEFT_PERIOD),
            listOf(KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_AT),
            listOf(
                InputModeSwitcher.USER_KEYCODE_SYMBOL,
                InputModeSwitcher.USER_KEYCODE_RETURN,
                KeyEvent.KEYCODE_0,
                KeyEvent.KEYCODE_SPACE,
                KeyEvent.KEYCODE_ENTER,
            ),
        ),
        bottomWidths = listOf(0.1694f, 0.2167f, 0.2167f, 0.2167f, 0.1694f),
    )

    val handwriting = LayoutSpec(
        codeRows = listOf(
            listOf(KeyEvent.KEYCODE_DEL),
            listOf(InputModeSwitcher.USER_KEYCODE_LEFT_SYMBOL),
            listOf(
                InputModeSwitcher.USER_KEYCODE_SYMBOL,
                InputModeSwitcher.USER_KEYCODE_NUMBER,
                KeyEvent.KEYCODE_SPACE,
                InputModeSwitcher.USER_KEYCODE_LANG,
                KeyEvent.KEYCODE_ENTER,
            ),
        ),
        visualRows = listOf(
            listOf(key(0.8417f, 0.0046f, 0.14259f, 0.1818f, 0.8417f, 0f, 1f, 0.1818f)),
            listOf(key(0.84726f, 0.1818f, 0.14259f, 0.634f, 0.8417f, 0.1818f, 1f, 0.817f)),
            bottomRow(
                top = 0.817f,
                starts = listOf(0.14814f, 0.29628f, 0.42868f, 0.69812f, 0.84626f),
                widths = listOf(0.14814f, 0.13240f, 0.26944f, 0.14814f, 0.14259f),
                height = 0.1702f,
                touchLeft = 0.14259f,
            ),
        ),
        drawingArea = NormalizedRect(0f, 0.0046f, 0.8417f, 0.81585f),
    )

    val textEdit = LayoutSpec(
        codeRows = listOf(
            listOf(
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                InputModeSwitcher.USER_KEYCODE_SELECT_ALL,
            ),
            listOf(InputModeSwitcher.USER_KEYCODE_SELECT_MODE, InputModeSwitcher.USER_KEYCODE_COPY),
            listOf(KeyEvent.KEYCODE_DPAD_DOWN, InputModeSwitcher.USER_KEYCODE_PASTE),
            listOf(InputModeSwitcher.USER_KEYCODE_MOVE_START, InputModeSwitcher.USER_KEYCODE_MOVE_END, KeyEvent.KEYCODE_DEL),
        ),
        visualRows = listOf(
            listOf(
                visualKey(0.005f, 0.005f, 0.245f, 0.74f),
                visualKey(0.255f, 0.005f, 0.245f, 0.24f),
                visualKey(0.505f, 0.005f, 0.245f, 0.74f),
                visualKey(0.755f, 0.005f, 0.24f, 0.24f),
            ),
            listOf(
                visualKey(0.255f, 0.255f, 0.245f, 0.24f),
                visualKey(0.755f, 0.255f, 0.24f, 0.24f),
            ),
            listOf(
                visualKey(0.255f, 0.505f, 0.245f, 0.24f),
                visualKey(0.755f, 0.505f, 0.24f, 0.24f),
            ),
            listOf(
                visualKey(0.005f, 0.755f, 0.3267f, 0.24f),
                visualKey(0.3367f, 0.755f, 0.3266f, 0.24f),
                visualKey(0.6683f, 0.755f, 0.3267f, 0.24f),
            ),
        ),
    )

    private fun t9StyleSpec(
        codeRows: List<List<Int>>,
        bottomWidths: List<Float>? = null,
        labelOverrides: Map<KeyPosition, LabelOverride> = emptyMap(),
    ): LayoutSpec {
        val rows = SogouT9Layout.rowGeometry
        val holder = key(
            left = SogouT9Layout.candidateCodeView.x,
            top = SogouT9Layout.candidateCodeView.y,
            width = SogouT9Layout.candidateCodeView.width,
            height = SogouT9Layout.candidateCodeView.height,
            touchLeft = 0f,
            touchTop = 0f,
            touchRight = SogouT9Layout.MAIN_START_X,
            touchBottom = rows[2].touchBottom,
        )
        val bottomGeometry = bottomWidths?.let { widths ->
            val starts = widths.runningFold(SogouT9Layout.VISUAL_START_X) { left, width -> left + width }
                .dropLast(1)
            bottomRow(
                top = SogouT9Layout.visualRowTops.last(),
                starts = starts,
                widths = widths,
                height = SogouT9Layout.VISUAL_BOTTOM_ROW_HEIGHT,
                touchLeft = 0f,
                touchTop = rows.last().touchTop,
            )
        } ?: rows[3].keys
        return LayoutSpec(
            codeRows = codeRows,
            visualRows = listOf(listOf(holder) + rows[0].keys, rows[1].keys, rows[2].keys, bottomGeometry),
            labelOverrides = labelOverrides,
        )
    }

    private fun bottomRow(
        top: Float,
        starts: List<Float>,
        widths: List<Float>,
        height: Float,
        touchLeft: Float,
        touchTop: Float = top,
    ): List<KeyGeometry> {
        val boundaries = starts.zip(widths).zipWithNext { left, right ->
            ((left.first + left.second) + right.first) / 2f
        }
        return starts.indices.map { index ->
            key(
                left = starts[index],
                top = top,
                width = widths[index],
                height = height,
                touchLeft = boundaries.getOrElse(index - 1) { touchLeft },
                touchTop = touchTop,
                touchRight = boundaries.getOrElse(index) { 1f },
                touchBottom = 1f,
            )
        }
    }

    private fun visualKey(left: Float, top: Float, width: Float, height: Float) =
        key(left, top, width, height, left, top, left + width, top + height)

    private fun key(
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        touchLeft: Float,
        touchTop: Float,
        touchRight: Float,
        touchBottom: Float,
    ) = KeyGeometry(left, top, width, height, touchLeft, touchTop, touchRight, touchBottom)
}
