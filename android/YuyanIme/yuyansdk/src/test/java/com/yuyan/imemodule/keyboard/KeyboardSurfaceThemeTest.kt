package com.yuyan.imemodule.keyboard

import android.view.KeyEvent
import com.yuyan.imemodule.manager.InputModeSwitcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardSurfaceThemeTest {

    @Test
    fun `四套主题顺序和显示名称与主题页一致`() {
        assertEquals(
            listOf("默认布局", "默认布局（蓝）", "微信布局", "搜狗布局 for 华为"),
            KeyboardSurfaceThemes.options.map { it.displayName },
        )
        assertEquals(
            listOf("SogouDefault", "SogouBlue", "WechatLayout", "SogouHuawei"),
            KeyboardSurfaceThemes.options.map { it.themeId },
        )
    }

    @Test
    fun `默认和蓝色共享几何而微信与华为声明各自底行`() {
        val orange = KeyboardSurfaceThemes.require("SogouDefault")
        val blue = KeyboardSurfaceThemes.require("SogouBlue")
        val wechat = KeyboardSurfaceThemes.require("WechatLayout")
        val huawei = KeyboardSurfaceThemes.require("SogouHuawei")

        assertEquals(orange.layoutFamily, blue.layoutFamily)
        assertEquals(KeyboardSurfaceLayoutFamily.WECHAT, wechat.layoutFamily)
        assertEquals(KeyboardSurfaceLayoutFamily.HUAWEI, huawei.layoutFamily)
        assertTrue(wechat.usesCompactFiveKeyQwertyBottomRow)
        assertFalse(huawei.usesCompactFiveKeyQwertyBottomRow)
    }

    @Test
    fun `四套浅色皮肤使用参考界面的主色和功能键色`() {
        assertEquals(0xfffb6d0e.toInt(), KeyboardSurfaceThemes.require("SogouDefault").accentColor)
        assertEquals(0xff007aff.toInt(), KeyboardSurfaceThemes.require("SogouBlue").accentColor)
        assertEquals(0xff23c891.toInt(), KeyboardSurfaceThemes.require("WechatLayout").accentColor)
        assertEquals(0xff0864f7.toInt(), KeyboardSurfaceThemes.require("SogouHuawei").accentColor)
        assertEquals(0xffcacbd7.toInt(), KeyboardSurfaceThemes.require("SogouDefault").functionKeyColor)
        assertEquals(0xffb6bbc4.toInt(), KeyboardSurfaceThemes.require("WechatLayout").functionKeyColor)
    }

    @Test
    fun `微信26键底行使用五键结构和参考宽度`() {
        val row = KeyboardSurfaceLayoutOverrides.qwertyBottomRow("WechatLayout")

        assertEquals(
            listOf(
                InputModeSwitcher.USER_KEYCODE_SYMBOL,
                InputModeSwitcher.USER_KEYCODE_LEFT_PERIOD,
                KeyEvent.KEYCODE_SPACE,
                InputModeSwitcher.USER_KEYCODE_LANG,
                KeyEvent.KEYCODE_ENTER,
            ),
            row.codes,
        )
        assertEquals(listOf(0.1889f, 0.087f, 0.3713f, 0.113f, 0.1889f), row.widths)
    }

    @Test
    fun `华为26键底行保留七键但调整中英和123位置`() {
        val row = KeyboardSurfaceLayoutOverrides.qwertyBottomRow("SogouHuawei")

        assertEquals(
            listOf(
                InputModeSwitcher.USER_KEYCODE_SYMBOL,
                InputModeSwitcher.USER_KEYCODE_LANG,
                InputModeSwitcher.USER_KEYCODE_LEFT_COMMA,
                KeyEvent.KEYCODE_SPACE,
                InputModeSwitcher.USER_KEYCODE_LEFT_PERIOD,
                InputModeSwitcher.USER_KEYCODE_NUMBER,
                KeyEvent.KEYCODE_ENTER,
            ),
            row.codes,
        )
    }

    @Test
    fun `微信九宫格回车从第三行跨到第四行且底行移除普通回车`() {
        val spec = KeyboardSurfaceLayoutOverrides.t9("WechatLayout")

        assertTrue(spec.bigEnter)
        assertEquals(0.48602f, spec.bigEnterHeight, 0.00001f)
        assertEquals(
            listOf(
                InputModeSwitcher.USER_KEYCODE_SYMBOL,
                InputModeSwitcher.USER_KEYCODE_NUMBER,
                KeyEvent.KEYCODE_SPACE,
                InputModeSwitcher.USER_KEYCODE_LANG,
            ),
            spec.bottomCodes,
        )
    }
}
