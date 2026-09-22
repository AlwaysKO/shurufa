package com.yuyan.inputmethod.util

import org.junit.Assert.*
import org.junit.Test

class T9SpellingTest {
    @Test fun `学习允许三键末音节补全但默认整句资格不扩大`() {
        assertEquals(setOf("966", "9663", "96636"), T9Spelling.completionCodes("wo men", minLength = 3))
        assertFalse("966" in T9Spelling.completionCodes("wo men"))
        assertFalse("963" in T9Spelling.completionCodes("zen me", minLength = 3))
        assertFalse("249" in T9Spelling.completionCodes("bu gao xing", minLength = 3))
    }

    @Test fun `共享学习编码只截短最后音节且至少四键`() {
        assertEquals(setOf("9366", "93663"), T9Spelling.completionCodes("zen'me"))
        assertEquals(setOf("64324862", "643248622"), T9Spelling.completionCodes("ni fa huo ba"))
        assertEquals(setOf("2466434262", "24664342622", "246643426226"), T9Spelling.completionCodes("chong dian bao"))
        assertFalse(T9Spelling.completionCodes("bu gao xing").contains("249"))
        assertTrue(T9Spelling.completionCodes("hou").isEmpty())
        assertTrue(T9Spelling.completionCodes("~zen'me").isEmpty())
    }

    @Test fun `截图中的四个扩写词均被拒绝`() {
        for (pinyin in listOf("bian'tian'xia", "bei'tiao'zheng", "beng'tiao'zhe", "bian'tiao'zhi")) {
            assertNull(pinyin, T9Spelling.preedit("284269", pinyin))
        }
    }
    @Test fun `允许末音节逐键输入且前面的拼音必须完整`() {
        assertEquals("bu'gao'x", T9Spelling.preedit("284269", "bu'gao'xing"))
        assertEquals("bu'gao'xi", T9Spelling.preedit("2842694", "bu'gao'xing"))
        assertEquals("bu'gao'xing", T9Spelling.preedit("284269464", "bu'gao'xing"))
        assertEquals("ni'zhe'y", T9Spelling.preedit("649439", "ni'zhe'yang"))
        assertNull(T9Spelling.preedit("249", "bu'gao'xing"))
        assertNull(T9Spelling.preedit("28426", "bu'gao'xing"))
    }
    @Test fun `完整前缀候选保留剩余按键供分段选字`() {
        assertEquals("bu'4269", T9Spelling.preedit("284269", "bu"))
        assertNull(T9Spelling.preedit("284269", "bian"))
    }
    @Test fun `空注释和非拼音不能当作完整读音`() {
        assertNull(T9Spelling.preedit("284269", ""))
        assertNull(T9Spelling.preedit("284269", "~xing"))
        assertEquals("bu'gao'x", T9Spelling.preedit("284269", "bu gao xing"))
    }
}
