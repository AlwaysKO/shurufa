package com.yuyan.inputmethod.util

import org.junit.Assert.*
import org.junit.Test

class T9PendingPinyinTest {
    @Test fun `已选汉字后没有分隔符也能展示剩余拼音`() {
        for (composition in listOf("我们MA", "真的MA", "你好MA", "wo'men'MA")) {
            assertTrue(composition, "ma" in T9PinYinUtils.t9KeyToPinyin(composition))
        }
        assertTrue("ling" in T9PinYinUtils.t9KeyToPinyin("泰JGMG"))
    }
    @Test fun `只取当前待选段且无剩余按键不伪造空拼音`() {
        assertArrayEquals(T9PinYinUtils.t9KeyToPinyin("MA"), T9PinYinUtils.t9KeyToPinyin("我们MA'DD"))
        for (composition in listOf("", "我们", "wo'men'", "XYZ")) {
            assertTrue(composition, T9PinYinUtils.t9KeyToPinyin(composition).isEmpty())
        }
    }
}
