package com.yuyan.imemodule.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SogouKeyboardTypographyTest {
    @Test
    fun `九宫格左侧标点使用源码42像素基准字号`() {
        assertEquals(42f, SogouKeyboardTypography.T9_SIDE_SYMBOL_SIZE, 0.001f)
    }

    @Test
    fun `1080宽度按源码51像素显示九宫格主字母`() {
        assertEquals(
            51f,
            SogouKeyboardTypography.mainTextSize(
                themeId = "WechatLayout",
                keyboardWidth = 1080,
                fontScale = 1f,
                referenceSize = 51f,
                fallbackSize = 32f,
            ),
            0.001f,
        )
    }

    @Test
    fun `搜狗键帽规格不受旧主题偏好影响并始终使用常规字重`() {
        assertFalse(SogouKeyboardTypography.useBold("SogouDefault", userBold = true, forceRegular = true))
        assertFalse(SogouKeyboardTypography.useBold("MaterialLight", userBold = true, forceRegular = true))
    }

    @Test
    fun `旧安装仍选中 MaterialLight 时搜狗键帽继续使用源码字号`() {
        assertEquals(
            51f,
            SogouKeyboardTypography.mainTextSize(
                themeId = "MaterialLight",
                keyboardWidth = 1080,
                fontScale = 1f,
                referenceSize = 51f,
                fallbackSize = 32f,
            ),
            0.001f,
        )
    }

    @Test
    fun `搜狗源码基线直接按键帽高度定位而不是再次做字体居中偏移`() {
        assertEquals(
            216.25f,
            SogouKeyboardTypography.labelBaseline(
                themeId = "SogouDefault",
                referenceSize = 51f,
                keyTop = 100f,
                keyHeight = 200f,
                bias = 0.58125f,
                ascent = -40f,
                descent = 10f,
            ),
            0.001f,
        )
    }

    @Test
    fun `没有源码字号的普通键继续按字体视觉中心计算基线`() {
        assertEquals(
            231.25f,
            SogouKeyboardTypography.labelBaseline(
                themeId = "MaterialLight",
                referenceSize = 0f,
                keyTop = 100f,
                keyHeight = 200f,
                bias = 0.58125f,
                ascent = -40f,
                descent = 10f,
            ),
            0.001f,
        )
    }
}
