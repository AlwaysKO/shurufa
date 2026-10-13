package com.yuyan.imemodule.data.completion

import com.yuyan.inputmethod.util.T9Spelling

/** 实际锁音记录；最后一段允许未完成，其余锁音必须完整匹配音节。 */
internal class T9InputConstraint private constructor(private val parts: List<String>) {
    val digits = parts.joinToString("") { T9Lexicon.digits(it) }

    fun matches(reading: String, allowPartial: Boolean = false): Boolean {
        val normalized = reading.trim().lowercase().replace('ü', 'v')
        if (!READING.matches(normalized)) return false
        val syllables = normalized.split(SEPARATORS)
        fun match(partIndex: Int, syllableIndex: Int): Boolean {
            if (partIndex == parts.size) return syllableIndex == syllables.size
            if (syllableIndex == syllables.size) return allowPartial
            val part = parts[partIndex]
            val last = partIndex == parts.lastIndex
            if (part.first().isLetter()) {
                val syllable = syllables[syllableIndex]
                val initial = part in INITIALS
                if (initial && !last && parts[partIndex + 1].all { it in '2'..'9' }) {
                    if (!syllable.startsWith(part)) return false
                    val code = T9Lexicon.digits(part) + parts[partIndex + 1]
                    for (end in syllableIndex + 1..syllables.size) {
                        val readingPart = syllables.subList(syllableIndex, end).joinToString(" ")
                        val full = T9Lexicon.digits(readingPart.replace(" ", ""))
                        val aligned = T9Spelling.preedit(code, readingPart)
                        val fits = if (partIndex + 1 == parts.lastIndex)
                            aligned != null && aligned.none(Char::isDigit) else full == code
                        if (fits && match(partIndex + 2, end)) return true
                        if (allowPartial && end == syllables.size && code.startsWith(full)) return true
                    }
                    return false
                }
                return (if (last && initial) syllable.startsWith(part) else syllable == part) &&
                    match(partIndex + 1, syllableIndex + 1)
            }
            for (end in syllableIndex + 1..syllables.size) {
                val remainingReading = syllables.subList(syllableIndex, end).joinToString(" ")
                val full = T9Lexicon.digits(remainingReading.replace(" ", ""))
                val fits = if (last) {
                    val aligned = T9Spelling.preedit(part, remainingReading)
                    aligned != null && aligned.none(Char::isDigit)
                } else full == part
                if (fits && match(partIndex + 1, end)) return true
                if (allowPartial && end == syllables.size && part.startsWith(full)) return true
            }
            return false
        }
        return match(0, 0)
    }

    companion object {
        private val READING = Regex("[a-zü]+(?:[' ]+[a-zü]+)*")
        private val SEPARATORS = Regex("[' ]+")
        private val INITIALS = setOf("b", "p", "m", "f", "d", "t", "n", "l", "g", "k", "h",
            "j", "q", "x", "zh", "ch", "sh", "r", "z", "c", "s", "y", "w")
        fun parse(input: String): T9InputConstraint? {
            if (input.length !in 1..60 || '\'' !in input || input.none { it in 'a'..'z' }) return null
            val parts = input.trimEnd('\'').split('\'')
            if (parts.any { it.isEmpty() || !(it.all { char -> char in 'a'..'z' } || it.all { char -> char in '2'..'9' }) }) return null
            return T9InputConstraint(parts).takeIf { it.digits.length in 1..30 }
        }
    }
}
