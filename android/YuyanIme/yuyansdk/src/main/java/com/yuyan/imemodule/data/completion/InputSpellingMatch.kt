package com.yuyan.imemodule.data.completion

import com.yuyan.inputmethod.util.T9Spelling

internal enum class InputMatchKind { THREE_INITIALS, PHRASE_PREFIX, WHOLE_PHRASE, INITIAL_FULL_WORD, TYPO_CORRECTION, SHORT_WORD }

/** 附在候选上的实际输入证据；完整读音另存，不把预测后缀伪装成已输入拼音。 */
internal data class InputSpellingMatch(val kind: InputMatchKind, val code: String, val preedit: String) {
    companion object {
        private val separators = Regex("[' ]+")
        private val spaces = Regex(" +")
        private val phrasePattern = Regex("[a-z]+(?: +[a-z]+)+")
        /** 二三字词的独立通道；三字混拼须打全首音节，或每字都只输入首字母。 */
        private fun isNormalizedReading(reading: String): Boolean {
            var previousSpace = true
            var hasSpace = false
            for (char in reading) {
                if (char in 'a'..'z') previousSpace = false
                else if (char == ' ' && !previousSpace) {
                    previousSpace = true
                    hasSpace = true
                } else return false
            }
            return hasSpace && !previousSpace
        }

        private fun numericKey(char: Char): Char = when (char) {
            in 'a'..'c' -> '2'; in 'd'..'f' -> '3'; in 'g'..'i' -> '4'
            in 'j'..'l' -> '5'; in 'm'..'o' -> '6'; in 'p'..'s' -> '7'
            in 't'..'v' -> '8'; else -> '9'
        }

        private fun matchesPart(code: String, offset: Int, syllable: String, length: Int, numeric: Boolean): Boolean {
            if (offset + length > code.length) return false
            for (i in 0 until length) {
                val char = syllable[i]
                val key = if (numeric) numericKey(char) else char
                if (code[offset + i] != key) return false
            }
            return true
        }

        fun shortWord(code: String, reading: String): InputSpellingMatch? {
            if (code.length !in 2..30) return null
            val numeric = code.all { it in '2'..'9' }
            if (!numeric && code.any { it !in 'a'..'z' }) return null
            val normalized = if (isNormalizedReading(reading)) reading else {
                val value = reading.trim().lowercase().replace('ü', 'v').replace(separators, " ")
                if (!isNormalizedReading(value)) return null
                value
            }
            val syllables = normalized.split(' ')
            if (syllables.size !in 2..3) return null
            val first = syllables[0]
            val middle = if (syllables.size == 3) syllables[1] else ""
            val last = syllables.last()
            var bestScore = -1
            var bestFirst = 0
            var bestMiddle = 0
            var bestLast = 0
            // 保持旧路径顺序：首字母在全音节之前；同分时保留最早路径。
            for (firstChoice in 0 until if (first.length == 1) 1 else 2) {
                val firstLength = if (firstChoice == 0) 1 else first.length
                if (!matchesPart(code, 0, first, firstLength, numeric)) continue
                for (middleChoice in 0 until if (middle.length <= 1) 1 else 2) {
                    val middleLength = if (middle.isEmpty()) 0 else if (middleChoice == 0) 1 else middle.length
                    if (!matchesPart(code, firstLength, middle, middleLength, numeric)) continue
                    val lastLength = code.length - firstLength - middleLength
                    if (lastLength !in 1..last.length ||
                        !matchesPart(code, firstLength + middleLength, last, lastLength, numeric)) continue
                    if (firstLength != first.length &&
                        !(firstLength == 1 && middleLength <= 1 && lastLength == 1) &&
                        !(syllables.size == 2 && lastLength == last.length)) continue
                    val score = (if (firstLength == first.length) 1 else 0) +
                        (if (middle.isNotEmpty() && middleLength == middle.length) 1 else 0) +
                        (if (lastLength == last.length) 1 else 0)
                    if (score > bestScore) {
                        bestScore = score
                        bestFirst = firstLength
                        bestMiddle = middleLength
                        bestLast = lastLength
                    }
                }
            }
            if (bestScore < 0) return null
            val preedit = buildString {
                append(first, 0, bestFirst)
                append('\'')
                if (middle.isNotEmpty()) {
                    append(middle, 0, bestMiddle)
                    append('\'')
                }
                append(last, 0, bestLast)
            }
            return InputSpellingMatch(InputMatchKind.SHORT_WORD, code, preedit)
        }
        /** 仅显示用途；普通全键候选也不能扩写未键入的字母。 */
        fun typedPrefix(code: String, reading: String): String? {
            if (code.isEmpty() || code.any { it !in 'a'..'z' }) return null
            val syllables = reading.trim().lowercase().replace('ü', 'v').split(separators)
            if (!syllables.joinToString("").startsWith(code)) return null
            var remaining = code.length
            return syllables.mapNotNull { syllable ->
                if (remaining <= 0) null else syllable.take(remaining).also { remaining -= it.length }
            }.joinToString("'")
        }

        fun match(code: String, reading: String, allowPhrase: Boolean = true): InputSpellingMatch? {
            val canonical = isNormalizedReading(reading)
            val normalized = if (canonical) reading else reading.trim().lowercase().replace('ü', 'v').replace('\'', ' ')
            if (!canonical && !phrasePattern.matches(normalized)) return null
            val numeric = code.isNotEmpty() && code.all { it in '2'..'9' }
            if (!numeric && (code.isEmpty() || code.any { it !in 'a'..'z' })) return null
            if (canonical && numeric && code.length == 3) {
                val firstSpace = normalized.indexOf(' ')
                val secondSpace = normalized.indexOf(' ', firstSpace + 1)
                if (secondSpace < 0 || normalized.indexOf(' ', secondSpace + 1) >= 0) return null
                val first = normalized[0]
                val second = normalized[firstSpace + 1]
                val third = normalized[secondSpace + 1]
                return if (numericKey(first) == code[0] && numericKey(second) == code[1] && numericKey(third) == code[2])
                    InputSpellingMatch(InputMatchKind.THREE_INITIALS, code, "$first'$second'$third") else null
            }
            val syllables = if (canonical) normalized.split(' ') else normalized.split(spaces)
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
