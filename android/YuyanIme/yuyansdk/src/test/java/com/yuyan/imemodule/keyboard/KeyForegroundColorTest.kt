package com.yuyan.imemodule.keyboard

import com.yuyan.imemodule.entity.keyboard.KeyType
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyForegroundColorTest {
    @Test
    fun `强调键的文字和图标使用强调前景色`() {
        assertEquals(
            ACCENT_COLOR,
            resolveKeyForegroundColor(KeyType.AccentKey, NORMAL_COLOR, ACCENT_COLOR),
        )
    }

    @Test
    fun `普通键和功能键使用普通前景色`() {
        assertEquals(NORMAL_COLOR, resolveKeyForegroundColor(KeyType.Normal, NORMAL_COLOR, ACCENT_COLOR))
        assertEquals(NORMAL_COLOR, resolveKeyForegroundColor(KeyType.Function, NORMAL_COLOR, ACCENT_COLOR))
    }

    companion object {
        private const val NORMAL_COLOR = 0x112233
        private const val ACCENT_COLOR = 0x445566
    }
}
