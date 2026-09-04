package com.yuyan.imemodule.keyboard

/** 搜狗 1080 设计稿中的键帽字号按当前键盘宽度等比缩放。 */
object SogouKeyboardTypography {
    const val REFERENCE_WIDTH = 1080
    const val T9_MAIN_LABEL_SIZE = 51f
    const val T9_SECONDARY_LABEL_SIZE = 24f
    const val T9_SIDE_SYMBOL_SIZE = 42f
    const val QWERTY_MAIN_LABEL_SIZE = 57f
    const val QWERTY_SECONDARY_LABEL_SIZE = 24f
    const val MAIN_LABEL_COLOR = 0xff000000.toInt()
    const val MINOR_LABEL_COLOR = 0x4a000000
    const val SWITCH_MINOR_LABEL_COLOR = 0xff81818e.toInt()

    fun mainTextSize(
        @Suppress("UNUSED_PARAMETER")
        themeId: String,
        keyboardWidth: Int,
        fontScale: Float,
        referenceSize: Float,
        fallbackSize: Float,
    ): Float = if (referenceSize > 0f) {
        referenceSize * keyboardWidth / REFERENCE_WIDTH * fontScale
    } else {
        fallbackSize
    }

    fun useBold(
        @Suppress("UNUSED_PARAMETER") themeId: String,
        userBold: Boolean,
        forceRegular: Boolean,
    ): Boolean = userBold && !forceRegular

    fun labelBaseline(
        themeId: String,
        referenceSize: Float,
        keyTop: Float,
        keyHeight: Float,
        bias: Float,
        ascent: Float,
        descent: Float,
    ): Float {
        val configuredBaseline = keyTop + keyHeight * bias
        return if (referenceSize > 0f) {
            configuredBaseline
        } else {
            configuredBaseline - (ascent + descent) / 2f
        }
    }
}
