package com.yuyan.imemodule.data.completion

import com.yuyan.inputmethod.util.T9Spelling
import org.junit.Assert.assertEquals
import org.junit.Test

class ShortWordSearchParityTest {
    private val spaces = Regex(" +")
    private val separators = Regex("[' ]+")
    private val phrasePattern = Regex("[a-z]+(?: +[a-z]+)+")
        private fun reference(code: String, reading: String): InputSpellingMatch? {
            val normalized = reading.trim().lowercase().replace('ü', 'v').replace(separators, " ")
            if (!phrasePattern.matches(normalized) || code.length !in 2..30) return null
            val numeric = code.all { it in '2'..'9' }
            if (!numeric && code.any { it !in 'a'..'z' }) return null
            val syllables = normalized.split(' ')
            if (syllables.size !in 2..3) return null
            var paths = listOf(emptyList<String>())
            for ((index, syllable) in syllables.withIndex()) {
                val lengths = if (index == syllables.lastIndex) (1..syllable.length).toList()
                    else listOf(1, syllable.length).distinct()
                paths = paths.flatMap { path ->
                    val offset = path.sumOf(String::length)
                    lengths.mapNotNull { length ->
                        val part = syllable.take(length)
                        val keys = if (numeric) T9Lexicon.digits(part) else part
                        if (code.startsWith(keys, offset)) path + part else null
                    }
                }
                if (paths.isEmpty()) return null
            }
            val typed = paths.filter { path ->
                path.sumOf(String::length) == code.length &&
                    (path.first() == syllables.first() || path.all { it.length == 1 } ||
                        (syllables.size == 2 && path.last() == syllables.last()))
            }.maxByOrNull { path -> path.zip(syllables).count { it.first == it.second } } ?: return null
            return InputSpellingMatch(InputMatchKind.SHORT_WORD, code, typed.joinToString("'"))
        }

    @Test fun `双字三字所有拼写路径与旧搜索结果完全相同`() {
        val syllables = listOf("a", "an", "nan", "shan", "xi", "xia", "zhi", "n")
        val readings = buildList {
            for (a in syllables) for (b in syllables) {
                add(listOf(a,b))
                for (c in syllables) add(listOf(a,b,c))
            }
        }
        for (parts in readings) {
            var typed = listOf("")
            for ((i, part) in parts.withIndex()) {
                val lengths = if (i == parts.lastIndex) (1..part.length).toList() else listOf(1,part.length).distinct()
                typed = typed.flatMap { prefix -> lengths.map { prefix + part.take(it) } }
            }
            val reading = parts.joinToString(" ")
            for (letters in typed.distinct()) for (code in listOf(letters, T9Lexicon.digits(letters), letters+"z", "2"+letters)) {
                assertEquals("$code / $reading", reference(code, reading), InputSpellingMatch.shortWord(code, reading))
            }
        }
    }

    @Test fun `非规范读音和非法编码保留旧处理`() {
        val readings = listOf(" NAN' SHAN ", "nan  shan", "nan'''shan", "lü se", " nan\tshan ",
            "nan", "", "nan shan an xi", "nan !", "nan  SHAN", "n a", "a a a", "NAΝ SHAN")
        val codes = listOf("nans", "6267", "ns", "67", "lvse", "5873", "aa", "22", "aaa", "222", "", "n1", "6'7", "a".repeat(31))
        for (reading in readings) for (code in codes) {
            assertEquals("$code / $reading", reference(code, reading), InputSpellingMatch.shortWord(code, reading))
        }
    }
        private fun referenceMatch(code: String, reading: String, allowPhrase: Boolean = true): InputSpellingMatch? {
            val normalized = reading.trim().lowercase().replace('ü', 'v').replace('\'', ' ')
            if (!phrasePattern.matches(normalized)) return null
            val syllables = normalized.split(spaces)
            val numeric = code.isNotEmpty() && code.all { it in '2'..'9' }
            if (!numeric && (code.isEmpty() || code.any { it !in 'a'..'z' })) return null
            // 独立的双字混拼证据：第一字仅首字母，第二字必须打全；不扩大长词内部简拼。
            if (numeric && code.length in 4..7 && syllables.size == 2 && syllables[0].length > 1 &&
                T9Lexicon.digits(syllables[0].take(1) + syllables[1]) == code &&
                T9Spelling.preedit(code, reading)?.none(Char::isDigit) != true) {
                return InputSpellingMatch(InputMatchKind.INITIAL_FULL_WORD, code, "${syllables[0].first()}'${syllables[1]}")
            }
            if (numeric && code.length == 3 && syllables.size == 3 &&
                T9Lexicon.digits(syllables.joinToString("") { it.take(1) }) == code) {
                return InputSpellingMatch(InputMatchKind.THREE_INITIALS, code, syllables.joinToString("'") { it.take(1) })
            }
            if (!allowPhrase || syllables.size !in 5..20) return null
            val full = syllables.joinToString("")
            val target = if (numeric) T9Lexicon.digits(full) else full
            val minimum = syllables.take(3).sumOf { it.length } + 1
            if (code.length !in minimum..target.length || !target.startsWith(code)) return null
            var remaining = code.length
            val typed = syllables.mapNotNull { syllable ->
                if (remaining <= 0) null else syllable.take(remaining).also { remaining -= it.length }
            }
            val kind = if (typed.size == syllables.size) InputMatchKind.WHOLE_PHRASE else InputMatchKind.PHRASE_PREFIX
            return InputSpellingMatch(kind, code, typed.joinToString("'"))
        }

    @Test fun `旧match规范快路径保持简拼混拼长句和脏读音结果`() {
        val readings = listOf("nan shan", "xi an", "jiu ke yi", "wu guan ai", "a a a", "lü se",
            "jin tian xia wu kai hui", "fei liu zhi xia san qian chi", "wo xiang yao yi ge",
            List(20) { "a" }.joinToString(" "), List(21) { "a" }.joinToString(" "))
        for (reading in readings) {
            val full = reading.replace(" ", "").replace('ü','v')
            val parts = reading.split(' ')
            val codes = (0..full.length).flatMap { length -> listOf(full.take(length), T9Lexicon.digits(full.take(length))) } +
                listOf(T9Lexicon.digits(parts.joinToString("") { it.take(1) }),
                    T9Lexicon.digits(parts[0].take(1) + parts[1]), "", "2a", "123")
            val variants = listOf(reading, " " + reading.uppercase() + " ", reading.replace(" ", "   "),
                reading.replace(" ", "'"), reading.replace(" ", "''"), reading.replace(" ", "\t"), reading + " !")
            for (variant in variants) for (code in codes) for (allow in listOf(true,false)) {
                assertEquals("$code / $variant / allow=$allow", referenceMatch(code, variant, allow), InputSpellingMatch.match(code, variant, allow))
            }
        }
    }
}
