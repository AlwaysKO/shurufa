package com.yuyan.imemodule.entity.keyboard

import com.yuyan.imemodule.singleton.EnvironmentSingleton
/**
 * Class used to represent a soft keyboard definition, including the height, the
 * background image, the image for high light, the keys, etc.
 * 一个软件盘的定义，包括按键的排列布局，宽度高度。
 * The width of the soft keyboard. 键盘的宽度
 * The height of the soft keyboard. 键盘的高度
 */
class SoftKeyboard(
    var mKeyRows: List<List<SoftKey>>,
    keyXMarginScale: Float = 1f,
    keyYMarginScale: Float = 1f,
) {
    data class NormalizedHitBounds(
        val key: SoftKey,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) {
        init {
            require(left in 0f..1f && right in 0f..1f && left < right)
            require(top in 0f..1f && bottom in 0f..1f && top < bottom)
        }
    }

    private data class PixelHitBounds(
        val key: SoftKey,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )

    /** 构造时一次性缓存为像素边界，触摸热路径不再读取或分配行几何。 */
    private var hitBounds: List<PixelHitBounds> = emptyList()

    /** Java/Kotlin 调用方长期使用的单参数入口。 */
    constructor(mKeyRows: List<List<SoftKey>>) : this(mKeyRows, 1f, 1f)

    /** 精确几何的模块内扩展入口，不改变原三参数 JVM 构造器。 */
    internal constructor(
        mKeyRows: List<List<SoftKey>>,
        keyXMarginScale: Float,
        keyYMarginScale: Float,
        normalizedHitBounds: List<NormalizedHitBounds>,
        keyboardWidth: Int,
        keyboardHeight: Int,
    ) : this(mKeyRows, keyXMarginScale, keyYMarginScale) {
        hitBounds = normalizedHitBounds.map { bounds ->
            require(keyboardWidth > 0 && keyboardHeight > 0)
            PixelHitBounds(
                key = bounds.key,
                left = (bounds.left * keyboardWidth).toInt(),
                top = (bounds.top * keyboardHeight).toInt(),
                right = (bounds.right * keyboardWidth).toInt(),
                bottom = (bounds.bottom * keyboardHeight).toInt(),
            )
        }
    }

    // 按键左右间隔距离
    val keyXMargin = (EnvironmentSingleton.instance.keyXMargin * keyXMarginScale).toInt()
    // 按键上下间隔距离
    val keyYMargin = (EnvironmentSingleton.instance.keyYMargin * keyYMarginScale).toInt()
    /**
     * 优先命中视觉核心；配置连续命中区的布局再按同一行中线边界接管视觉间隙。
     * 未配置连续命中区的布局维持原行为，键盘外或按键外返回 null。
     */
    fun mapToKey(x: Int, y: Int): SoftKey? {
        for (element in mKeyRows) {
            for (sKey in element) {
                if (sKey.mLeft <= x && sKey.mTop <= y && sKey.mRight > x && sKey.mBottom > y) return sKey
            }
        }
        for (bounds in hitBounds) {
            if (bounds.left <= x && bounds.top <= y && bounds.right > x && bounds.bottom > y) {
                return bounds.key
            }
        }
        return null
    }

    /**
     * 根据code值查询按键，由于符号键无code&部分键盘可能存在重复键，因此该方式可能无法精确查询。
     */
    fun getKeyByCode(code: Int): SoftKey? {
        for (keyRow in mKeyRows) {
            for (sKey in keyRow) {
                if (sKey.code == code) return sKey
            }
        }
        return null
    }
}
