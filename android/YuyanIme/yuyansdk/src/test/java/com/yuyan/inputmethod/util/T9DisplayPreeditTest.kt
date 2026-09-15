package com.yuyan.inputmethod.util

import org.junit.Assert.*
import org.junit.Test

class T9DisplayPreeditTest {
    private fun display(code: String, reading: String) =
        T9Spelling.displayComposition(T9Spelling.preedit(code, reading) ?: code, true)

    @Test fun `未解析九宫格禁止原样提交而其他键盘不受影响`() {
        assertTrue(T9Spelling.hasUnresolvedComposition("ming'tian'78727", true))
        assertFalse(T9Spelling.hasUnresolvedComposition("ming'tian'qu'pa's", true))
        assertFalse(T9Spelling.hasUnresolvedComposition("abc123", false))
        assertEquals("abc123", T9Spelling.displayComposition("abc123", false))
        assertEquals("", T9Spelling.displayComposition("", true))
    }

    @Test fun `明天去爬山末字首字母和全拼显示实际输入`() {
        assertEquals("ming'tian'qu'pa's", display("6464842678727", "ming tian qu pa shan"))
        assertEquals("ming'tian'qu'pa'shan", display("6464842678727426", "ming tian qu pa shan"))
    }
    @Test fun `没有完整候选读音时保留等价拼读而不是隐藏或展开键帽`() {
        for ((code, reading) in listOf("6464842678727" to "ming tian", "96353" to "wo", "6464842678727" to "")) {
            val shown = display(code, reading)
            assertTrue(shown, shown.all { it in 'a'..'z' || it == '\'' })
            assertEquals(code, com.yuyan.imemodule.data.completion.T9Lexicon.digits(shown.replace("'", "")))
        }
        assertEquals("wo'e'ke", display("96353", "wo")) // 缺少读音时按既有表顺序，不假装知道应当是le。
    }
    @Test fun `显示不改变前缀候选资格和剩余按键对齐`() {
        assertTrue(T9Spelling.allows("6464842678727", "明天", "ming tian"))
        assertEquals("ming'tian'78727", T9Spelling.preedit("6464842678727", "ming tian"))
        assertFalse(T9Spelling.allows("6464842678727", "明天去爬山", "m t qu pa shan"))
    }
}
