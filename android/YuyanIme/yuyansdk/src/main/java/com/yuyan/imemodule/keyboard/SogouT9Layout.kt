package com.yuyan.imemodule.keyboard

import android.view.KeyEvent
import com.yuyan.imemodule.entity.keyboard.LongPressAction
import com.yuyan.imemodule.entity.keyboard.KeyType
import com.yuyan.imemodule.entity.keyboard.SoftKey
import com.yuyan.imemodule.entity.keyboard.SoftKeyToggle
import com.yuyan.imemodule.entity.keyboard.ToggleState
import com.yuyan.imemodule.manager.InputModeSwitcher

/** 中文九宫格的紧凑几何与键位规格。 */
object SogouT9Layout {
    data class NormalizedRect(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
    ) {
        val right: Float get() = x + width
        val bottom: Float get() = y + height
    }

    /** 应用层原有的屏幕高度比例，保留供偏好设置使用。 */
    const val KEYBOARD_HEIGHT_RATIO = 0.278f
    /** APK 9.ini 中相对于键盘宽度的高度。 */
    const val KEYBOARD_HEIGHT_TO_WIDTH_RATIO = 0.5944f
    const val CANDIDATE_TEXT_SIZE_PERCENT = 45

    const val VISUAL_START_X = 0.0056f
    const val MAIN_START_X = 0.175f
    const val VISUAL_RIGHT_COLUMN_WIDTH = 0.1694f
    const val VISUAL_MAIN_KEY_WIDTH = 0.2167f
    const val VISUAL_MAIN_KEY_HEIGHT = 0.24922f
    const val VISUAL_CANDIDATE_HEIGHT = 0.7477f
    const val VISUAL_BOTTOM_ROW_HEIGHT = 0.2368f

    /** KeyboardLoaderUtil 尚未逐键应用 APK 几何时的运行时兼容常量。 */
    const val START_X = 0.005f
    const val SIDE_WIDTH = 0.17f
    const val MAIN_WIDTH = 0.21666667f
    const val ROW_HEIGHT = 0.245f
    const val SIDE_HEIGHT = 0.735f

    /** 项目 SoftKey 背景内边距的渲染兼容值，不是 APK ini 几何。 */
    const val X_MARGIN_SCALE = 0.7f
    const val Y_MARGIN_SCALE = 0.8f

    private val visualRowTopValues = floatArrayOf(0.0078f, 0.2570f, 0.5062f, 0.7555f)
    val visualRowTops: List<Float> get() = visualRowTopValues.toList()
    private val visualRowStartXValues = floatArrayOf(MAIN_START_X, MAIN_START_X, MAIN_START_X, VISUAL_START_X)
    val visualRowStartXs: List<Float> get() = visualRowStartXValues.toList()
    val candidateCodeView = NormalizedRect(
        x = VISUAL_START_X,
        y = visualRowTopValues[0],
        width = VISUAL_RIGHT_COLUMN_WIDTH,
        height = VISUAL_CANDIDATE_HEIGHT,
    )

    /** KeyboardLoaderUtil 当前使用的连续列宽。 */
    private val runtimeColumnWidthValues = floatArrayOf(SIDE_WIDTH, MAIN_WIDTH, MAIN_WIDTH, MAIN_WIDTH, SIDE_WIDTH)
    val columnWidths: List<Float> get() = runtimeColumnWidthValues.toList()
    private val runtimeColumnLeftEdgeValues = runtimeColumnWidthValues.runningFold(START_X) { left, width -> left + width }
        .dropLast(1)
        .toFloatArray()
    val columnLeftEdges: List<Float> get() = runtimeColumnLeftEdgeValues.toList()
    private val runtimeColumnRightEdgeValues = runtimeColumnWidthValues.runningFold(START_X) { left, width -> left + width }
        .drop(1)
        .toFloatArray()
    val columnRightEdges: List<Float> get() = runtimeColumnRightEdgeValues.toList()

    private val visualColumnWidthValues = floatArrayOf(
        VISUAL_RIGHT_COLUMN_WIDTH,
        VISUAL_MAIN_KEY_WIDTH,
        VISUAL_MAIN_KEY_WIDTH,
        VISUAL_MAIN_KEY_WIDTH,
        VISUAL_RIGHT_COLUMN_WIDTH,
    )
    val visualColumnWidths: List<Float> get() = visualColumnWidthValues.toList()
    private val visualColumnRightEdgeValues = visualColumnWidthValues
        .runningFold(VISUAL_START_X) { left, width -> left + width }
        .drop(1)
        .toFloatArray()
    val visualColumnRightEdges: List<Float> get() = visualColumnRightEdgeValues.toList()

    /** 运行时键码保存在私有真值中，对外始终返回防御副本。 */
    private val rightColumnCodeValues = intArrayOf(KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_CLEAR, KeyEvent.KEYCODE_0)
    val rightColumnCodes: IntArray get() = rightColumnCodeValues.copyOf()
    private val keyRowValues = arrayOf(
        intArrayOf(
            InputModeSwitcher.USER_KEYCODE_LEFT_SYMBOL,
            KeyEvent.KEYCODE_APOSTROPHE,
            KeyEvent.KEYCODE_A,
            KeyEvent.KEYCODE_D,
            rightColumnCodeValues[0],
        ),
        intArrayOf(KeyEvent.KEYCODE_G, KeyEvent.KEYCODE_J, KeyEvent.KEYCODE_M, rightColumnCodeValues[1]),
        intArrayOf(KeyEvent.KEYCODE_P, KeyEvent.KEYCODE_T, KeyEvent.KEYCODE_W, rightColumnCodeValues[2]),
    )
    val keyRows: Array<IntArray> get() = Array(keyRowValues.size) { index -> keyRowValues[index].copyOf() }

    private val bottomRowCodeValues = intArrayOf(
        InputModeSwitcher.USER_KEYCODE_SYMBOL,
        InputModeSwitcher.USER_KEYCODE_NUMBER,
        KeyEvent.KEYCODE_SPACE,
        InputModeSwitcher.USER_KEYCODE_LANG,
        KeyEvent.KEYCODE_ENTER,
    )
    val bottomRowCodes: IntArray get() = bottomRowCodeValues.copyOf()
    private val visualBottomRowWidthValues = floatArrayOf(0.1694f, 0.1630f, 0.3241f, 0.1630f, 0.1694f)
    val visualBottomRowWidths: List<Float> get() = visualBottomRowWidthValues.toList()

    /** KeyboardLoaderUtil 当前连续排列所需的运行时宽度。 */
    private val runtimeBottomRowWidthValues = floatArrayOf(0.17f, 0.165f, 0.32f, 0.165f, 0.17f)
    val bottomRowWidths: List<Float> get() = runtimeBottomRowWidthValues.toList()

    private val visualRowGeometry = buildKeyboardGeometry(
        listOf(
            RowGeometrySpec(
                top = visualRowTopValues[0],
                startX = MAIN_START_X,
                keyWidths = List(3) { VISUAL_MAIN_KEY_WIDTH } + VISUAL_RIGHT_COLUMN_WIDTH,
                keyHeight = VISUAL_MAIN_KEY_HEIGHT,
                touchLeft = MAIN_START_X,
            ),
            RowGeometrySpec(
                top = visualRowTopValues[1],
                startX = MAIN_START_X,
                keyWidths = List(3) { VISUAL_MAIN_KEY_WIDTH } + VISUAL_RIGHT_COLUMN_WIDTH,
                keyHeight = VISUAL_MAIN_KEY_HEIGHT,
                touchLeft = MAIN_START_X,
            ),
            RowGeometrySpec(
                top = visualRowTopValues[2],
                startX = MAIN_START_X,
                keyWidths = List(3) { VISUAL_MAIN_KEY_WIDTH } + VISUAL_RIGHT_COLUMN_WIDTH,
                keyHeight = VISUAL_MAIN_KEY_HEIGHT,
                touchLeft = MAIN_START_X,
            ),
            RowGeometrySpec(
                top = visualRowTopValues[3],
                startX = VISUAL_START_X,
                keyWidths = visualBottomRowWidthValues.toList(),
                keyHeight = VISUAL_BOTTOM_ROW_HEIGHT,
            ),
        )
    )
    val rowGeometry: List<RowGeometry> get() = visualRowGeometry.toList()

    val mainRowRightEdge: Float
        get() = visualRowGeometry.first().keys.last().right
    val bottomRowRightEdge: Float
        get() = visualRowGeometry.last().keys.last().right

    fun applyBottomRowGeometry(keys: List<SoftKey>) {
        require(keys.size == bottomRowWidths.size)
        keys.forEachIndexed { index, key ->
            key.widthF = bottomRowWidths[index]
            key.heightF = ROW_HEIGHT
        }
    }

    fun createVoiceSpaceKey() = SoftKey(code = KeyEvent.KEYCODE_SPACE).apply {
        heightF = ROW_HEIGHT
        longPressAction = LongPressAction.Voice
    }

    fun createEnterKey() = SoftKeyToggle(KeyEvent.KEYCODE_ENTER).apply {
        heightF = ROW_HEIGHT
        keyType = KeyType.AccentKey
        stateId = 0
        preferTextLabel = true
        setToggleStates(
            listOf(
                ToggleState("换行", 0),
                ToggleState("换行", 1),
                ToggleState("去往", 2),
                ToggleState("搜索", 3),
                ToggleState("发送", 4),
                ToggleState("下一个", 5),
                ToggleState("完成", 6),
                ToggleState("上一个", 7),
            )
        )
    }
}
