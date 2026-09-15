package com.yuyan.imemodule.data.completion

import com.yuyan.inputmethod.util.T9Spelling

/** 只接受逐字、带音节边界的已知读音；不由数字或汉字猜拼音。 */
internal object PersonalWordReading {
    fun normalize(text: String, reading: String): String? {
        if (text.length !in 1..30 || text.any { it !in '\u4e00'..'\u9fff' }) return null
        val normalized = reading.trim().lowercase().replace('ü', 'v').replace(Regex("[' ]+"), " ")
        if (!Regex("[a-z]+(?: [a-z]+)*").matches(normalized)) return null
        return normalized.takeIf { it.split(' ').size == text.length }
    }
    fun matches(code: String, reading: String): Boolean =
        code == T9Lexicon.digits(reading.replace(" ", "")) || code in T9Spelling.completionCodes(reading)
}
