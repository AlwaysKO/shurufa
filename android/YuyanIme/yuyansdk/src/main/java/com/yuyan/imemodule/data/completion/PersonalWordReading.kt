package com.yuyan.imemodule.data.completion

import com.yuyan.inputmethod.util.T9Spelling

/** 只接受逐字、带音节边界的已知读音；不由数字或汉字猜拼音。 */
internal object PersonalWordReading {
    private val separators = Regex("[' ]+")
    private val readingPattern = Regex("[a-z]+(?: [a-z]+)*")
    fun normalize(text: String, reading: String): String? {
        if (text.length !in 1..30 || text.any { it !in '\u4e00'..'\u9fff' }) return null
        var syllables = 1
        var previousSpace = true
        var canonical = reading.isNotEmpty()
        for (char in reading) {
            if (char in 'a'..'z') previousSpace = false
            else if (char == ' ' && !previousSpace) {
                previousSpace = true
                syllables++
            } else {
                canonical = false
                break
            }
        }
        if (canonical && !previousSpace) return reading.takeIf { syllables == text.length }
        val normalized = reading.trim().lowercase().replace('ü', 'v').replace(separators, " ")
        if (!readingPattern.matches(normalized)) return null
        return normalized.takeIf { it.split(' ').size == text.length }
    }
    fun matches(code: String, reading: String): Boolean =
        code == T9Lexicon.digits(reading.replace(" ", "")) || code in T9Spelling.completionCodes(reading, minLength = 3) ||
            InputSpellingMatch.match(code, reading) != null || InputSpellingMatch.shortWord(code, reading) != null
}
