package com.yuyan.imemodule.data.completion

import kotlin.math.pow
import com.yuyan.inputmethod.util.T9Spelling

internal data class ChoiceEvidence(val text: String, val weight: Double, val updatedAt: Long, val lastSelectedAt: Long? = null)
internal data class RankedCandidate(
    val text: String, val pinyin: String = "", val nativeIndex: Int? = null,
    val inputMatch: InputSpellingMatch? = null,
)

/** 编码内平滑选择概率：近期明确选择优先，其余按基础先验 + 衰减选中次数。 */
internal object PersonalCandidateRanker {
    const val HALF_LIFE_MS = 14L * 24 * 60 * 60 * 1000
    const val RECENT_CHOICE_MS = 24L * 60 * 60 * 1000

    /** 只接收真实同码选择时间，不能拿聚合权重的计算时间冒充最近选词。 */
    fun recentSelection(lastSelectedAt: Long?, now: Long): Long =
        lastSelectedAt?.takeIf { now >= it && now - it <= RECENT_CHOICE_MS } ?: Long.MIN_VALUE

    fun decay(weight: Double, updatedAt: Long, now: Long): Double =
        weight * 0.5.pow((now - updatedAt).coerceAtLeast(0).toDouble() / HALF_LIFE_MS)

    fun rank(base: List<RankedCandidate>, history: List<ChoiceEvidence>, now: Long): List<RankedCandidate> {
        val unique = linkedMapOf<String, RankedCandidate>()
        base.forEach { candidate ->
            val previous = unique[candidate.text]
            unique[candidate.text] = if (previous == null) candidate else previous.copy(
                nativeIndex = previous.nativeIndex ?: candidate.nativeIndex,
                pinyin = previous.pinyin.ifBlank { candidate.pinyin },
                inputMatch = previous.inputMatch ?: candidate.inputMatch,
            )
        }
        val baseSize = unique.size
        history.forEach { unique.putIfAbsent(it.text, RankedCandidate(it.text)) }
        val evidence = history.associate { it.text to decay(it.weight, it.updatedAt, now) }
        val recent = history.associate { it.text to recentSelection(it.lastSelectedAt, now) }
        return unique.values.withIndex().sortedWith(compareByDescending<IndexedValue<RankedCandidate>> {
            recent[it.value.text] ?: Long.MIN_VALUE
        }.thenByDescending { (index, candidate) ->
            val prior = when {
                index == 0 && baseSize > 0 -> 2.0
                index < baseSize -> 1.0 / (index + 1)
                else -> 0.0
            }
            prior + (evidence[candidate.text] ?: 0.0)
        }).map { it.value }
    }
}

/** 首屏去重/重排后，后续页仍沿用 Rime 的连续原生索引。索引不包含手工候选。 */
internal class CandidateSelection(
    val firstPage: List<RankedCandidate>, private val nativeCount: Int,
    private val excludedTexts: Set<String> = emptySet(),
    private val extraMatch: (String, String) -> InputSpellingMatch? = { _, _ -> null },
    private val acceptNative: (String, String) -> Boolean = { _, _ -> true },
) {
    private val followingPages = mutableListOf<RankedCandidate>()
    private var nextNativeIndex = nativeCount

    fun appendNativePage(texts: List<String>, code: String, comments: List<String>? = null): List<Int> {
        val visible = texts.indices.filter {
            val match = extraMatch(texts[it], comments?.getOrNull(it).orEmpty())
            (match != null || isT9CandidateAllowed(code, texts[it])) && acceptNative(texts[it], comments?.getOrNull(it).orEmpty()) &&
                (match != null ||
                (comments?.getOrNull(it)?.let { reading -> T9Spelling.allows(code, texts[it], reading) }
                    ?: if (comments == null) texts[it] !in excludedTexts else T9Spelling.allows(code, texts[it], "")))
        }
        visible.forEach { index ->
            val reading = comments?.getOrNull(index).orEmpty()
            followingPages.add(RankedCandidate(texts[index], reading, nativeIndex = nextNativeIndex + index,
                inputMatch = extraMatch(texts[index], reading)))
        }
        nextNativeIndex += texts.size
        return visible
    }

    fun at(index: Int): RankedCandidate? = when {
        index < 0 -> null
        index < firstPage.size -> firstPage[index]
        index < firstPage.size + followingPages.size -> followingPages[index - firstPage.size]
        else -> RankedCandidate("", nativeIndex = nextNativeIndex + index - firstPage.size - followingPages.size)
    }
}
