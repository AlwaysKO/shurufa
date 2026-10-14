package com.yuyan.imemodule.data.completion

import org.junit.Assert.assertEquals
import org.junit.Test

class T9LexiconMatchParityTest {
    private val lexicon = T9Lexicon.parse("".reader())
    private val method = T9Lexicon::class.java.getDeclaredMethod("match", List::class.java, String::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
    private fun reference(syllables: List<String>, code: String, trailingOnly: Boolean = false): Int? {
        var states = mapOf(0 to 0)
        for ((index, syllable) in syllables.withIndex()) {
            val next = HashMap<Int, Int>()
            for ((offset, penalty) in states) {
                if (code.startsWith(syllable, offset)) {
                    val end = offset + syllable.length
                    next[end] = minOf(next[end] ?: Int.MAX_VALUE, penalty)
                }
                if ((!trailingOnly || index == syllables.lastIndex) &&
                    syllable.length > 1 && code.getOrNull(offset) == syllable.first()) {
                    val prefixLengths = if (index == syllables.lastIndex) 1 until syllable.length else 1..1
                    for (length in prefixLengths) {
                        if (code.startsWith(syllable.take(length), offset)) {
                            val end = offset + length
                            next[end] = minOf(next[end] ?: Int.MAX_VALUE, penalty + 1)
                        }
                    }
                }
            }
            if (next.isEmpty()) return null
            states = next
        }
        return states[code.length]
    }

    @Test fun `数组匹配保持数字字母简拼末音节前缀和最小惩罚`() {
        val units = listOf("a", "an", "nan", "shan", "xi", "xia", "zhi", "n")
        val phrases = buildList {
            add(emptyList<String>())
            add(listOf(""))
            for (a in units) for (b in units) {
                add(listOf(a,b))
                for (c in units) add(listOf(a,b,c))
            }
            add(listOf("jin", "tian", "xia", "wu", "kai", "hui"))
            add(listOf("a", "an", "an", "a", "an"))
            add(listOf("nan", "shan", "an", "xi"))
        }
        for (letters in phrases) for (parts in listOf(letters, letters.map(T9Lexicon::digits))) {
            var paths = listOf("")
            for ((index, syllable) in parts.withIndex()) {
                val lengths = if (index == parts.lastIndex) (1..syllable.length).toList() else listOf(1,syllable.length).distinct()
                paths = paths.flatMap { prefix -> lengths.map { prefix + syllable.take(it) } }
            }
            val full = parts.joinToString("")
            val codes = (paths + (0..full.length).map(full::take) + listOf(full+"z", "!", "123", "")).distinct()
            for (code in codes) for (trailing in listOf(true,false)) {
                assertEquals("$parts / $code / trailing=$trailing", reference(parts,code,trailing), method.invoke(lexicon,parts,code,trailing))
            }
        }
    }
}
