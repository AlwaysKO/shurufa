package com.yuyan.inputmethod.util

import com.yuyan.imemodule.data.completion.T9Lexicon

/** 按原生候选的实际读音对齐按键，不从汉字词表猜测读音。 */
internal object T9Spelling {
    private val readingPattern = Regex("[a-zü]+(?:[' ]+[a-zü]+)*")

    /** 学习只能在同一读音的完整码与末音节前缀间共享；不含三键短码或内部简拼。 */
    fun completionCodes(reading: String): Set<String> {
        val normalized = reading.trim().lowercase()
        if (!readingPattern.matches(normalized)) return emptySet()
        val syllables = normalized.replace('ü', 'v').split(Regex("[' ]+"))
        if (syllables.size < 2) return emptySet()
        val prefix = T9Lexicon.digits(syllables.dropLast(1).joinToString(""))
        val last = T9Lexicon.digits(syllables.last())
        return (1..last.length).map { prefix + last.take(it) }
            .filter { it.length in 4..30 }.toSet()
    }

    fun preedit(code: String, reading: String): String? {
        if (code.isEmpty() || code.any { it !in '2'..'9' }) return null
        val normalized = reading.trim().lowercase()
        if (!readingPattern.matches(normalized)) return null
        val syllables = normalized.replace('ü', 'v').split(Regex("[' ]+"))
        var offset = 0
        val typed = mutableListOf<String>()
        for ((index, syllable) in syllables.withIndex()) {
            val digits = T9Lexicon.digits(syllable)
            if (code.startsWith(digits, offset)) {
                typed.add(syllable)
                offset += digits.length
            } else {
                val remaining = code.substring(offset)
                // 只有最后一个音节能尚未打完；不能把前面任何音节补成长拼音。
                if (index != syllables.lastIndex || remaining.isEmpty() || !digits.startsWith(remaining)) return null
                typed.add(syllable.take(remaining.length))
                offset = code.length
            }
        }
        // 完整前缀词仍可选，剩余数字保留待输入状态，不擅自扩写。
        if (offset < code.length) typed.add(code.substring(offset))
        return typed.joinToString("'")
    }

    fun allows(code: String, text: String, reading: String): Boolean {
        if (code.length !in 3..30 || code.any { it !in '2'..'9' }) return true
        var offset = 0
        while (offset < text.length) {
            val point = text.codePointAt(offset)
            if (!Character.isIdeographic(point)) return true
            offset += Character.charCount(point)
        }
        if (text.isEmpty()) return true
        return preedit(code, reading) != null
    }
}
