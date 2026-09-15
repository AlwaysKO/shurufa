package com.yuyan.imemodule.keyboard

import org.junit.Assert.assertThrows
import org.junit.Test

class KeyboardGeometryTest {
    @Test
    fun `拒绝没有按键的行`() {
        assertThrows(IllegalArgumentException::class.java) {
            RowGeometry(top = 0f, keys = emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            RowGeometrySpec(top = 0f, startX = 0f, keyWidths = emptyList(), keyHeight = 0.2f)
        }
    }

    @Test
    fun `拒绝超出归一化范围的视觉键`() {
        assertThrows(IllegalArgumentException::class.java) {
            KeyGeometry(
                left = -0.1f,
                top = 0f,
                width = 0.2f,
                height = 0.2f,
                touchLeft = 0f,
                touchTop = 0f,
                touchRight = 0.2f,
                touchBottom = 0.2f,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            KeyGeometry(
                left = 0f,
                top = 0.9f,
                width = 0.2f,
                height = 0.2f,
                touchLeft = 0f,
                touchTop = 0.8f,
                touchRight = 0.2f,
                touchBottom = 1f,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            buildKeyboardGeometry(
                listOf(
                    RowGeometrySpec(
                        top = 0f,
                        startX = 0.9f,
                        keyWidths = listOf(0.2f),
                        keyHeight = 0.2f,
                    )
                )
            )
        }
    }

    @Test
    fun `拒绝行顶倒序的键盘规格`() {
        assertThrows(IllegalArgumentException::class.java) {
            buildKeyboardGeometry(
                listOf(
                    RowGeometrySpec(0.5f, 0f, listOf(0.2f), 0.2f),
                    RowGeometrySpec(0.1f, 0f, listOf(0.2f), 0.2f),
                )
            )
        }
    }

    @Test
    fun `拒绝倒置的触摸跨度`() {
        assertThrows(IllegalArgumentException::class.java) {
            RowGeometrySpec(
                top = 0f,
                startX = 0f,
                keyWidths = listOf(0.2f),
                keyHeight = 0.2f,
                touchLeft = 0.8f,
                touchRight = 0.2f,
            )
        }
    }
}
