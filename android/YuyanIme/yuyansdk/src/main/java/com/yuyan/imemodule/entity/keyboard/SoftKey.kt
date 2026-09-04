package com.yuyan.imemodule.entity.keyboard

import android.graphics.drawable.Drawable
import android.view.KeyEvent
import com.yuyan.imemodule.keyboard.keyIconRecords
import com.yuyan.imemodule.manager.InputModeSwitcher
import java.util.Objects

/**
 * 按键的属性
 */
open class SoftKey(var code: Int = 0, var label: String = "", var labelSmall: String = "", var keyMnemonic: String = ""){

    /** 键盘上下左右位置百分比 ，mLeft = (int) (mLeftF * skbWidth);  */
    var mLeftF = -1f
    var mTopF = -1f
    var widthF = 0f
    var heightF = 0.25f

    /** 键盘上下左右位置坐标边界; */
    var mLeft = 0
    var mRight = 0
    var mTop = 0
    var mBottom = 0

    var stateId = 0
    var pressed = false
    var keyType = KeyType.Normal
    var longPressAction = LongPressAction.Default
    var preferTextLabel = false

    /** 仅明确适配的键位启用自定义文字层级，其他键保持项目原有渲染。 */
    var useCustomLabelLayout = false
    var mainLabelScale = 1f
    var secondaryLabelScale = 1f
    var mainLabelVerticalBias = 0.5f
    var secondaryLabelVerticalBias = 0.5f
    var mainLabelHorizontalBias = 0.5f
    var secondaryLabelHorizontalBias = 0.5f
    /** 搜狗 1080 设计稿中的绝对字号；0 表示继续使用项目原字号。 */
    var mainLabelReferenceSize = 0f
    var secondaryLabelReferenceSize = 0f
    var forceRegularMainLabel = false
    /** 指定键位可覆盖主题前景色，以还原源布局的文字层级。 */
    var mainLabelColorOverride: Int? = null
    var secondaryLabelColorOverride: Int? = null

    fun onPressed() {
        pressed = true
    }

    fun onReleased() {
        pressed = false
    }

    fun setKeyDimensions(left: Float, top: Float) {
        mLeftF = left
        mTopF = top
    }
    fun setKeyDimensions(left: Float, top: Float, height:Float) {
        setKeyDimensions(left, top)
        heightF = height
    }

    /**
     * 设置按键的区域
     */
    fun setSkbCoreSize(skbWidth: Int, skbHeight: Int) {
        mLeft = (mLeftF * skbWidth).toInt()
        mRight = ((mLeftF + widthF) * skbWidth).toInt()
        mTop = (mTopF * skbHeight).toInt()
        mBottom = ((mTopF + heightF) * skbHeight).toInt()
    }

    open val keyIcon: Drawable?
        get() = keyIconRecords[Objects.hash(code, stateId)]

    open val keyLabel: String
        get() =  label

    fun getmKeyLabelSmall(): String {
        return labelSmall
    }

    fun getkeyLabel(): String {
        return label
    }

    open fun changeCase(upperCase: Boolean) {
        label = if (upperCase) label.uppercase() else label.lowercase()
    }

    val isKeyCodeKey: Boolean
        get() = code > 0

    val isUserDefKey: Boolean
        get() = code < 0

    val isUniStrKey: Boolean
        get() = code == 0

    fun repeatable(): Boolean {
        return code == KeyEvent.KEYCODE_DEL
                || code == InputModeSwitcher.USER_KEYCODE_CURSOR_DIRECTION
                || code in KeyEvent.KEYCODE_DPAD_UP .. KeyEvent.KEYCODE_DPAD_RIGHT
    }

    fun width(): Int {
        return mRight - mLeft
    }

    fun height(): Int {
        return mBottom - mTop
    }
}
