package com.yuyan.imemodule.keyboard

import android.view.KeyEvent
import com.yuyan.imemodule.manager.InputModeSwitcher

enum class KeyboardSurfaceLayoutFamily {
    SOGOU,
    WECHAT,
    HUAWEI,
}

/**
 * 键盘表面的分层主题规格。颜色来自参考预览的主色块，图形和预览由项目自行绘制。
 */
data class KeyboardSurfaceTheme(
    val themeId: String,
    val displayName: String,
    val layoutFamily: KeyboardSurfaceLayoutFamily,
    val keyboardColor: Int,
    val keyColor: Int,
    val functionKeyColor: Int,
    val accentColor: Int,
    val textColor: Int = 0xff000000.toInt(),
    val minorTextColor: Int = 0xff9d9d9f.toInt(),
    val usesCompactFiveKeyQwertyBottomRow: Boolean = false,
)

object KeyboardSurfaceThemes {
    val options = listOf(
        KeyboardSurfaceTheme(
            themeId = "SogouDefault",
            displayName = "默认布局",
            layoutFamily = KeyboardSurfaceLayoutFamily.SOGOU,
            keyboardColor = 0xfff4f4f8.toInt(),
            keyColor = 0xffffffff.toInt(),
            functionKeyColor = 0xffcacbd7.toInt(),
            accentColor = 0xfffb6d0e.toInt(),
        ),
        KeyboardSurfaceTheme(
            themeId = "SogouBlue",
            displayName = "默认布局（蓝）",
            layoutFamily = KeyboardSurfaceLayoutFamily.SOGOU,
            keyboardColor = 0xfff1f3f7.toInt(),
            keyColor = 0xffffffff.toInt(),
            functionKeyColor = 0xffcacbd9.toInt(),
            accentColor = 0xff007aff.toInt(),
        ),
        KeyboardSurfaceTheme(
            themeId = "WechatLayout",
            displayName = "微信布局",
            layoutFamily = KeyboardSurfaceLayoutFamily.WECHAT,
            keyboardColor = 0xffdddfe4.toInt(),
            keyColor = 0xffffffff.toInt(),
            functionKeyColor = 0xffb6bbc4.toInt(),
            accentColor = 0xff23c891.toInt(),
            usesCompactFiveKeyQwertyBottomRow = true,
        ),
        KeyboardSurfaceTheme(
            themeId = "SogouHuawei",
            displayName = "搜狗布局 for 华为",
            layoutFamily = KeyboardSurfaceLayoutFamily.HUAWEI,
            keyboardColor = 0xffdadbe0.toInt(),
            keyColor = 0xffffffff.toInt(),
            functionKeyColor = 0xffb6bbc4.toInt(),
            accentColor = 0xff0864f7.toInt(),
        ),
    )

    private val byId = options.associateBy(KeyboardSurfaceTheme::themeId)

    fun fromThemeId(themeId: String): KeyboardSurfaceTheme? = byId[themeId]

    fun require(themeId: String): KeyboardSurfaceTheme = requireNotNull(fromThemeId(themeId)) {
        "Unknown keyboard surface theme: $themeId"
    }
}

data class KeyboardBottomRowSpec(
    val codes: List<Int>,
    val widths: List<Float>,
)

data class KeyboardT9ThemeSpec(
    val bigEnter: Boolean,
    val bigEnterHeight: Float,
    val bottomCodes: List<Int>,
    val bottomWidths: List<Float>,
)

/** 只保存主题间不同的键位，其他行继续复用 SogouT9/Qwerty 的公共规格。 */
object KeyboardSurfaceLayoutOverrides {
    private val defaultQwerty = KeyboardBottomRowSpec(
        codes = SogouQwertyLayout.bottomRowCodes.toList(),
        widths = SogouQwertyLayout.visualBottomRowWidths,
    )
    private val wechatQwerty = KeyboardBottomRowSpec(
        codes = listOf(
            InputModeSwitcher.USER_KEYCODE_SYMBOL,
            InputModeSwitcher.USER_KEYCODE_LEFT_PERIOD,
            KeyEvent.KEYCODE_SPACE,
            InputModeSwitcher.USER_KEYCODE_LANG,
            KeyEvent.KEYCODE_ENTER,
        ),
        widths = listOf(0.1889f, 0.087f, 0.3713f, 0.113f, 0.1889f),
    )
    private val huaweiQwerty = KeyboardBottomRowSpec(
        codes = listOf(
            InputModeSwitcher.USER_KEYCODE_SYMBOL,
            InputModeSwitcher.USER_KEYCODE_LANG,
            InputModeSwitcher.USER_KEYCODE_LEFT_COMMA,
            KeyEvent.KEYCODE_SPACE,
            InputModeSwitcher.USER_KEYCODE_LEFT_PERIOD,
            InputModeSwitcher.USER_KEYCODE_NUMBER,
            KeyEvent.KEYCODE_ENTER,
        ),
        widths = SogouQwertyLayout.visualBottomRowWidths,
    )

    fun qwertyBottomRow(themeId: String): KeyboardBottomRowSpec = when (
        KeyboardSurfaceThemes.fromThemeId(themeId)?.layoutFamily
    ) {
        KeyboardSurfaceLayoutFamily.WECHAT -> wechatQwerty
        KeyboardSurfaceLayoutFamily.HUAWEI -> huaweiQwerty
        else -> defaultQwerty
    }

    fun qwertyBottomGeometry(themeId: String): RowGeometry = buildKeyboardGeometry(
        listOf(
            RowGeometrySpec(
                top = SogouQwertyLayout.visualRowTops[3],
                startX = SogouQwertyLayout.visualRowStartXs[3],
                keyWidths = qwertyBottomRow(themeId).widths,
                keyHeight = SogouQwertyLayout.VISUAL_KEY_HEIGHT,
                horizontalGap = SogouQwertyLayout.VISUAL_HORIZONTAL_GAP,
            ),
        ),
    ).single()

    fun t9(themeId: String): KeyboardT9ThemeSpec {
        val wechat = KeyboardSurfaceThemes.fromThemeId(themeId)?.layoutFamily == KeyboardSurfaceLayoutFamily.WECHAT
        return if (wechat) {
            KeyboardT9ThemeSpec(
                bigEnter = true,
                bigEnterHeight = 0.48602f,
                bottomCodes = SogouT9Layout.bottomRowCodes.dropLast(1),
                bottomWidths = SogouT9Layout.visualBottomRowWidths.dropLast(1),
            )
        } else {
            KeyboardT9ThemeSpec(
                bigEnter = false,
                bigEnterHeight = SogouT9Layout.VISUAL_MAIN_KEY_HEIGHT,
                bottomCodes = SogouT9Layout.bottomRowCodes.toList(),
                bottomWidths = SogouT9Layout.visualBottomRowWidths,
            )
        }
    }

    fun t9BottomGeometry(themeId: String): RowGeometry = buildKeyboardGeometry(
        listOf(
            RowGeometrySpec(
                top = SogouT9Layout.visualRowTops.last(),
                startX = SogouT9Layout.VISUAL_START_X,
                keyWidths = t9(themeId).bottomWidths,
                keyHeight = SogouT9Layout.VISUAL_BOTTOM_ROW_HEIGHT,
            ),
        ),
    ).single()
}
