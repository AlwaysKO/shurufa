package com.yuyan.imemodule.data.completion

import java.io.Reader
import kotlin.math.ln

/** Bounded fallback combinations; scores are search heuristics, not sentence frequencies. */
internal class T9SentenceFallback private constructor(
    private val fullWords: Map<String, List<Word>>,
    private val initials: Map<String, List<Word>>,
    private val endings: Map<String, List<Word>>,
    private val completeSyllables: Set<String>,
    private val syllablePrefixes: Set<String>,
) {
    data class Sentence(val text: String, val pinyin: String, val typedPreedit: String, val score: Double)
    data class DecodeResult(val candidates: List<Sentence>, val expandedEdges: Int)
    private data class Word(val text: String, val pinyin: String, val code: String, val frequency: Long)
    private data class Edge(val word: Word, val consumed: Int, val typed: String, val penalty: Double)
    private data class ChunkPath(val score: Double, val previous: ChunkPath?, val sentence: Sentence)
    fun decode(code: String): List<Sentence> = decodeWithStats(code).candidates
    /** Frequency-independent guard: only the final syllable may still be incomplete. */
    fun canSpellWithoutInternalAbbreviation(code: String): Boolean {
        if (code.isEmpty() || code.any { it !in '2'..'9' }) return false
        val reachable = BooleanArray(code.length + 1)
        reachable[0] = true
        for (offset in code.indices) {
            if (!reachable[offset]) continue
            for (end in offset + 1..minOf(code.length, offset + 6)) {
                val part = code.substring(offset, end)
                if (end == code.length && part in syllablePrefixes) return true
                if (part in completeSyllables) reachable[end] = true
            }
        }
        return reachable.last()
    }
    fun decodeWithStats(code: String): DecodeResult {
        if (code.length < 8 || code.any { it !in '2'..'9' }) return DecodeResult(emptyList(), 0)
        if (code.length <= 48) return decodeChunk(code)
        var paths = emptyList<ChunkPath>()
        var expanded = 0
        var offset = 0
        while (offset < code.length) {
            val end = minOf(code.length, offset + 48)
            val chunk = decodeChunk(code.substring(offset, end))
            expanded += chunk.expandedEdges
            if (chunk.candidates.isEmpty()) return DecodeResult(emptyList(), expanded)
            val combinations = if (paths.isEmpty()) chunk.candidates.map {
                ChunkPath(it.score, null, it)
            } else paths.flatMap { previous ->
                chunk.candidates.map { ChunkPath(previous.score + it.score, previous, it) }
            }
            expanded += combinations.size
            paths = combinations.sortedByDescending { it.score }.take(4)
            offset = end
        }
        val candidates = paths.map { path ->
            val chunks = ArrayList<Sentence>()
            var node: ChunkPath? = path
            while (node != null) {
                chunks.add(node.sentence)
                node = node.previous
            }
            chunks.reverse()
            Sentence(chunks.joinToString("") { it.text }, chunks.joinToString(" ") { it.pinyin },
                chunks.joinToString("'") { it.typedPreedit }, path.score)
        }.distinctBy { it.text }
        return DecodeResult(candidates, expanded)
    }
    /** Each independent chunk has a fixed beam and at most 48 consumed keys. */
    private fun decodeChunk(code: String): DecodeResult {
        val paths = Array(code.length + 1) { mutableListOf<Sentence>() }
        paths[0].add(Sentence("", "", "", 0.0))
        var expanded = 0
        for (offset in code.indices) {
            if (paths[offset].isEmpty()) continue
            val edges = ArrayList<Edge>(72)
            for (length in 1..minOf(16, code.length - offset)) {
                fullWords[code.substring(offset, offset + length)].orEmpty().forEach { word ->
                    edges.add(Edge(word, length, word.pinyin.replace(' ', '\''), 0.0))
                }
            }
            initials[code.substring(offset, offset + 1)].orEmpty().forEach { word ->
                if (word.code.length > 1) edges.add(Edge(word, 1, word.pinyin.take(1), 8.0))
            }
            val remaining = code.substring(offset)
            if (remaining.length <= 6) endings[remaining].orEmpty().forEach { word ->
                if (remaining.length < word.code.length && remaining.length > 1)
                    edges.add(Edge(word, remaining.length, word.pinyin.take(remaining.length), 3.0))
            }
            for (path in paths[offset]) for (edge in edges) {
                expanded++
                if (path.text.length + edge.word.text.length > 48) continue
                val word = edge.word
                val sentence = Sentence(path.text + word.text,
                    if (path.pinyin.isEmpty()) word.pinyin else path.pinyin + " " + word.pinyin,
                    if (path.typedPreedit.isEmpty()) edge.typed else path.typedPreedit + "'" + edge.typed,
                    path.score + ln(word.frequency.toDouble() + 1) - 16.0 - edge.penalty)
                val destination = paths[offset + edge.consumed]
                val old = destination.indexOfFirst { it.text == sentence.text }
                if (old >= 0 && destination[old].score >= sentence.score) continue
                if (old >= 0) destination.removeAt(old)
                destination.add(sentence)
                destination.sortByDescending { it.score }
                if (destination.size > 4) destination.removeAt(destination.lastIndex)
            }
        }
        return DecodeResult(paths.last().toList(), expanded)
    }
    companion object {
        private fun digits(reading: String): String = buildString {
            reading.forEach { if (it in 'a'..'z') append("22233344455566677778889999"[it - 'a']) }
        }
        fun load(reader: Reader): T9SentenceFallback {
            val words = reader.buffered().lineSequence().mapNotNull { line ->
                val fields = line.split('\t')
                if (fields.size != 3) return@mapNotNull null
                val text = fields[0]
                val pinyin = fields[1]
                val syllables = pinyin.split(' ')
                val frequency = fields[2].toLongOrNull() ?: return@mapNotNull null
                if (text.length !in 1..4 || syllables.size != text.length || frequency < 0 ||
                    text.any { !Character.isIdeographic(it.code) } ||
                    syllables.any { it.isEmpty() || it.any { char -> char !in 'a'..'z' } }) return@mapNotNull null
                val code = digits(pinyin)
                if (code.length > 16) null else Word(text, pinyin, code, frequency)
            }.toList()
            fun top(items: List<Word>): List<Word> = items.sortedWith(
                compareByDescending<Word> { it.frequency }.thenBy { it.text }.thenBy { it.pinyin }
            ).distinctBy { it.text }.take(4)
            val singles = words.filter { it.text.length == 1 }
            val completeSyllables = singles.mapTo(hashSetOf()) { it.code }
            val syllablePrefixes = completeSyllables.flatMapTo(hashSetOf()) { code ->
                (1..code.length).map { code.take(it) }
            }
            val endings = mutableMapOf<String, MutableList<Word>>()
            singles.forEach { word ->
                for (length in 2 until word.code.length) endings.getOrPut(word.code.take(length)) { mutableListOf() }.add(word)
            }
            return T9SentenceFallback(words.groupBy { it.code }.mapValues { top(it.value) },
                singles.groupBy { it.code.take(1) }.mapValues { top(it.value) },
                endings.mapValues { top(it.value) }, completeSyllables, syllablePrefixes)
        }
    }
}
