package com.yuyan.imemodule.data.completion

import java.io.Reader
import kotlin.math.ln

internal data class T9Candidate(val text: String, val pinyin: String, val frequency: Long = 0)

/** 四字以上的拼写候选至少要有一个字输入多于一键；不限制上屏后的联想。 */
internal fun isT9CandidateAllowed(code: String, text: String): Boolean {
    if (code.length < 4 || code.any { it !in '2'..'9' }) return true
    var offset = 0
    var count = 0
    while (offset < text.length) {
        val point = text.codePointAt(offset)
        if (!Character.isIdeographic(point)) return true
        count++
        offset += Character.charCount(point)
    }
    return count < code.length
}

/** 有音节边界的本地词典：九宫格只允许最后一个音节简拼，不扩写前面的音节。 */
internal class T9Lexicon private constructor(entries: List<T9Candidate>) {
    private data class Entry(val candidate: T9Candidate, val syllables: List<String>)
    private val knownTexts = entries.mapTo(hashSetOf()) { it.text }

    /** 收录依据不受当前编码长度或首屏八条截断影响，短词前缀和后页同样可确认。 */
    fun containsText(text: String): Boolean = text in knownTexts

    /** 临时构建本次导入词的读音映射，不给全库额外保留逐词索引。 */
    fun readings(texts: Set<String>): Map<String, List<String>> = if (texts.isEmpty()) emptyMap() else
        buckets.values.asSequence().flatten().filter { it.candidate.text in texts }
            .map { it.candidate }.groupBy({ it.text }, { it.pinyin })

    private val buckets = entries.map { Entry(it, it.pinyin.split(' ').map(::digits)) }
        .groupBy { it.syllables.first().first() }

    private val pinyinBuckets = entries.map { Entry(it, it.pinyin.split(' ')) }
        .groupBy { it.syllables.first().first() }

    fun queryPinyin(code: String, limit: Int = 8): List<T9Candidate> {
        if (code.length !in 2..30 || code.any { it !in 'a'..'z' }) return emptyList()
        return queryEntries(pinyinBuckets[code.first()].orEmpty(), code, limit, true)
    }

    fun query(
        code: String,
        limit: Int = 8,
        allowAbbreviations: Boolean = true,
        includeTexts: Set<String> = emptySet(),
        onSpellingMatch: (String, Boolean) -> Unit = { _, _ -> },
    ): List<T9Candidate> {
        if (code.length !in 3..30 || code.any { it !in '2'..'9' }) return emptyList()
        return queryEntries(buckets[code.first()].orEmpty(), code, limit, allowAbbreviations, onSpellingMatch, includeTexts)
    }

    private fun queryEntries(
        entries: List<Entry>, code: String, limit: Int, allowAbbreviations: Boolean,
        onSpellingMatch: (String, Boolean) -> Unit = { _, _ -> },
        includeTexts: Set<String> = emptySet(),
    ): List<T9Candidate> {
        return entries.asSequence()
            .filter { isT9CandidateAllowed(code, it.candidate.text) }
            .filter { code.length in it.syllables.size..it.syllables.sumOf(String::length) }
            .mapNotNull { entry ->
                val penalty = match(entry.syllables, code) ?: return@mapNotNull null
                val allowed = code.first() !in '2'..'9' || penalty == 0 ||
                    match(entry.syllables, code, trailingOnly = true) != null
                // sortedWith 会遍历全部匹配项，排除集不受首屏数量限制。
                onSpellingMatch(entry.candidate.text, allowed)
                if (allowed) entry.candidate to penalty else null
            }
            .filter { allowAbbreviations || it.second == 0 }
            .sortedWith(compareByDescending<Pair<T9Candidate, Int>> { ln(1.0 + it.first.frequency) - 0.7 * it.second }
                .thenBy { it.second }.thenBy { it.first.text })
            // 首屏上限只限制普通召回；合法历史词仍参与个人排序。
            .filterIndexed { index, pair -> index < limit || pair.first.text in includeTexts }
            .map { it.first }.toList()
    }

    /** 返回最少简拼音节数；每层最多保留 code.length 个状态，避免组合爆炸。 */
    private fun match(syllables: List<String>, code: String, trailingOnly: Boolean = false): Int? {
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

    companion object {
        fun digits(pinyin: String): String = pinyin.lowercase().map { c ->
            when (c) {
                in 'a'..'c' -> '2'; in 'd'..'f' -> '3'; in 'g'..'i' -> '4'
                in 'j'..'l' -> '5'; in 'm'..'o' -> '6'; in 'p'..'s' -> '7'
                in 't'..'v' -> '8'; in 'w'..'z' -> '9'; else -> c
            }
        }.joinToString("")

        fun parse(reader: Reader): T9Lexicon = T9Lexicon(reader.buffered().lineSequence().mapNotNull { line ->
            val fields = line.split('\t')
            if (fields.size != 3 || !fields[1].matches(Regex("[a-z]+( [a-z]+)+"))) return@mapNotNull null
            val frequency = fields[2].toLongOrNull() ?: return@mapNotNull null
            T9Candidate(fields[0], fields[1], frequency)
        }.toList())
    }
}
