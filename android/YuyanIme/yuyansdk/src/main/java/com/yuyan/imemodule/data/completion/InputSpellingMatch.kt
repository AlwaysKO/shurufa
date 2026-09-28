package com.yuyan.imemodule.data.completion

import com.yuyan.inputmethod.util.T9Spelling

internal enum class InputMatchKind { THREE_INITIALS, PHRASE_PREFIX, WHOLE_PHRASE, INITIAL_FULL_WORD }

/** 附在候选上的实际输入证据；完整读音另存，不把预测后缀伪装成已输入拼音。 */
internal data class InputSpellingMatch(val kind: InputMatchKind, val code: String, val preedit: String) {
    companion object {
        /** 仅显示用途；普通全键候选也不能扩写未键入的字母。 */
        fun typedPrefix(code: String, reading: String): String? {
            if (code.isEmpty() || code.any { it !in 'a'..'z' }) return null
            val syllables = reading.trim().lowercase().replace('ü', 'v').split(Regex("[' ]+"))
            if (!syllables.joinToString("").startsWith(code)) return null
            var remaining = code.length
            return syllables.mapNotNull { syllable ->
                if (remaining <= 0) null else syllable.take(remaining).also { remaining -= it.length }
            }.joinToString("'")
        }

        fun match(code: String, reading: String, allowPhrase: Boolean = true): InputSpellingMatch? {
            val normalized = reading.trim().lowercase().replace('ü', 'v').replace('\'', ' ')
            if (!Regex("[a-z]+(?: +[a-z]+)+").matches(normalized)) return null
            val syllables = normalized.split(Regex(" +"))
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
    }
}
