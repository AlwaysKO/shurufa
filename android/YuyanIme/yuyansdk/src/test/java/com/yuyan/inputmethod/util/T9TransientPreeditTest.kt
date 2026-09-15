package com.yuyan.inputmethod.util

import com.yuyan.imemodule.data.completion.T9Lexicon
import org.junit.Assert.*
import org.junit.Test

class T9TransientPreeditTest {
    private fun assertReadable(code: String, displayed: String) {
        assertTrue(displayed, displayed.all { it in 'a'..'z' || it == '\'' })
        assertEquals(displayed, code, T9Lexicon.digits(displayed.replace("'", "")))
    }

    @Test fun `无实际读音时按已有拼音表取等价拼读但有读音时立即优先读音`() {
        for ((code, expected) in listOf("7" to "p", "74" to "pi", "748" to "qiu")) {
            assertEquals(expected, T9Spelling.fullDisplayComposition(code, code, emptyList()))
        }
        assertEquals("wo'e'le", T9Spelling.fullDisplayComposition("96353", "wo'353", listOf("wo e le")))
    }

    @Test(timeout = 5000) fun `整句话的所有中间阶段和长码都能保留全部按键`() {
        val code = T9Lexicon.digits("wodangshixiangshuru")
        // 同时覆盖“时”和“输”的s/sh阶段，不只测最后一个字。
        for (length in (0..code.length).toList() + (code.length downTo 0).toList()) {
            val partial = code.take(length)
            assertReadable(partial, T9Spelling.fullDisplayComposition(partial, partial, emptyList()))
        }
        val longCode = code.repeat(32)
        val started = System.nanoTime()
        assertReadable(longCode, T9Spelling.fullDisplayComposition(longCode, longCode, emptyList()))
        println("LONG_PREEDIT\t${longCode.length}\t${(System.nanoTime() - started) / 1_000_000.0}ms JVM，不是真机延迟")
    }

    @Test fun `我当时想输入逐键候选暂缺也不能显示键帽组或丢键`() {
        val prefix = "wo'dang'shi'xiang"
        val base = T9Lexicon.digits(prefix.replace("'", ""))
        val tail = T9Lexicon.digits("shuru")
        for (typed in (0..tail.length).toList() + (tail.length downTo 0).toList() + (0..tail.length).toList()) {
            val code = base + tail.take(typed)
            val raw = T9Spelling.preedit(code, prefix)!!
            val displayed = T9Spelling.fullDisplayComposition(code, raw, listOf(prefix))
            assertReadable(code, displayed)
            assertTrue(displayed, displayed.startsWith(prefix))
            assertEquals(raw, T9Spelling.preedit(code, prefix))
            assertEquals(typed > 0, T9Spelling.hasUnresolvedComposition(raw, true))
        }
    }

    @Test fun `候选给出实际读音后优先实际拼音且不扩写末音节`() {
        val prefix = "wo'dang'shi'xiang"
        val base = T9Lexicon.digits(prefix.replace("'", ""))
        for ((keys, suffix) in listOf("7" to "s", "74" to "sh", "748" to "shu")) {
            val code = base + keys
            assertEquals("$prefix'$suffix", T9Spelling.fullDisplayComposition(code, "$prefix'$keys", listOf("$prefix'shu")))
        }
    }

    @Test fun `空候选短码与不可拼读键串都保留按键但不展开键帽`() {
        var codes = listOf("")
        repeat(4) {
            codes = codes.flatMap { prefix -> ('2'..'9').map { prefix + it } }
            for (code in codes) assertReadable(code, T9Spelling.fullDisplayComposition(code, code, emptyList()))
        }
        val screenshotCode = "365266885699"
        assertReadable(screenshotCode, T9Spelling.fullDisplayComposition(screenshotCode, "fo'lao'6885699", listOf("fo'lao")))
    }
}
