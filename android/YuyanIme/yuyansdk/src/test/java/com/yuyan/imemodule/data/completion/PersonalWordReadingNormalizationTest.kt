package com.yuyan.imemodule.data.completion

import org.junit.Assert.*
import org.junit.Test

class PersonalWordReadingNormalizationTest {
    @Test fun `规范词库读音直接复用字符串`() {
        val reading = charArrayOf('n', 'a', 'n', ' ', 's', 'h', 'a', 'n').concatToString()
        assertSame(reading, PersonalWordReading.normalize("南山", reading))
    }

    @Test fun `快速路径和原规范化保持相同结果`() {
        val separators = Regex("[' ]+")
        val pattern = Regex("[a-z]+(?: [a-z]+)*")
        for (text in listOf("南山", "很多例子", "女", "南A", "", "南".repeat(31))) {
            for (reading in listOf("nan shan", " NAN  SHAN ", "nan'shan", "hen duo li zi", "nü", "nv", "n v", "nan\tshan", "nan shan ", " nan shan", "nan  shan", "nan 山", "")) {
                val normalized = reading.trim().lowercase().replace('ü', 'v').replace(separators, " ")
                val expected = normalized.takeIf {
                    text.length in 1..30 && text.all { c -> c in '\u4e00'..'\u9fff' } &&
                        pattern.matches(it) && it.split(' ').size == text.length
                }
                assertEquals("$text/$reading", expected, PersonalWordReading.normalize(text, reading))
            }
        }
    }
}
