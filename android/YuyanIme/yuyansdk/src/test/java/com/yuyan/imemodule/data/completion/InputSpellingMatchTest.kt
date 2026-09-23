package com.yuyan.imemodule.data.completion

import com.yuyan.inputmethod.util.T9Spelling
import org.junit.Assert.*
import org.junit.Test

class InputSpellingMatchTest {
    @Test fun `简拼显示依据词条读音而非键帽首字母`() {
        assertEquals("j'k'y", InputSpellingMatch.match("559", "jiu ke yi")?.preedit)
        assertEquals("l'k'z", InputSpellingMatch.match("559", "liang kou zhen")?.preedit)
        assertNull(InputSpellingMatch.match("559", "jiu ke"))
        assertNull(InputSpellingMatch.match("558", "jiu ke yi"))
        assertNull(InputSpellingMatch.match("649439", "mei guo zui gao fa yuan"))
        assertNull(InputSpellingMatch.match("284269", "chang tiao xing"))
        assertNull(InputSpellingMatch.match("5'59", "jiu ke yi"))
        assertNull(InputSpellingMatch.match("59", "ke yi"))
    }

    @Test fun `长句显示只包含已经输入部分`() {
        val reading = "fei liu zhi xia san qian chi"
        for (code in listOf("feiliuzhix", "3345489449")) {
            val match = requireNotNull(InputSpellingMatch.match(code, reading))
            assertEquals(InputMatchKind.PHRASE_PREFIX, match.kind)
            assertEquals(code, match.code)
            assertEquals("fei'liu'zhi'x", match.preedit)
        }
        assertEquals("fei'liu'zhi'x", T9Spelling.fullDisplayComposition("3345489449", "fei'liu'zhi'x", emptyList()))
        assertEquals(InputMatchKind.WHOLE_PHRASE, InputSpellingMatch.match("feiliuzhixiasanqianchi", reading)?.kind)
        assertEquals(InputMatchKind.WHOLE_PHRASE, InputSpellingMatch.match("feiliuzhixiasanqianc", reading)?.kind)
        assertNull(InputSpellingMatch.match("feiliuzhi", reading))
        assertNull(InputSpellingMatch.match("feiliuzhixb", reading))
        assertNull(InputSpellingMatch.match("33454894492", reading))
        assertNull(InputSpellingMatch.match("feiliuzhix", reading, allowPhrase = false))
    }

    @Test fun `普通全键候选也不能显示未输入的尾音`() {
        assertEquals("fei'liu'zhi'x", InputSpellingMatch.typedPrefix("feiliuzhix", "fei liu zhi xia"))
        assertEquals("ni", InputSpellingMatch.typedPrefix("ni", "ni hao"))
        assertEquals("xu'q", InputSpellingMatch.typedPrefix("xuq", "xu qiu"))
        assertNull(InputSpellingMatch.typedPrefix("feiliuzhib", "fei liu zhi xia"))
    }

    @Test fun `长句补全只在成功上屏后交出真实输入码`() {
        val tracker = T9CommitTracker()
        val text = "飞流直下三千尺"
        val reading = "fei liu zhi xia san qian chi"
        tracker.selected("3345489449", text, reading)
        assertNull(tracker.consumeSelection(text, false))
        tracker.selected("3345489449", text, reading)
        val selected = tracker.consumeSelection(text, true)
        assertEquals("3345489449", selected?.code)
        assertEquals(reading, selected?.pinyin)
        assertNull(tracker.consumeSelection(text, true))
        tracker.segment("559", "就可以", "jiu ke yi", "就可以")
        assertEquals("jiu ke yi", tracker.consumeSelection("就可以", true)?.pinyin)
    }
}
