package com.yuyan.imemodule.data.completion

import kotlin.math.pow
import com.yuyan.inputmethod.util.T9Spelling

internal data class ChoiceEvidence(val text: String, val weight: Double, val updatedAt: Long)
internal data class RankedCandidate(val text: String, val pinyin: String = "", val nativeIndex: Int? = null)

/** 编码内平滑选择概率：基础先验 + 衰减选中次数。公共分母不影响排名。 */
internal object PersonalCandidateRanker {
    const val HALF_LIFE_MS = 14L * 24 * 60 * 60 * 1000
    fun decay(weight: Double, updatedAt: Long, now: Long): Double =
        weight * 0.5.pow((now - updatedAt).coerceAtLeast(0).toDouble() / HALF_LIFE_MS)

    fun rank(base: List<RankedCandidate>, history: List<ChoiceEvidence>, now: Long): List<RankedCandidate> {
        val unique = linkedMapOf<String, RankedCandidate>()
        base.forEach { candidate ->
            val previous = unique[candidate.text]
            unique[candidate.text] = if (previous == null) candidate else previous.copy(
                nativeIndex = previous.nativeIndex ?: candidate.nativeIndex,
                pinyin = previous.pinyin.ifBlank { candidate.pinyin },
            )
        }
        val baseSize = unique.size
        history.forEach { unique.putIfAbsent(it.text, RankedCandidate(it.text)) }
        val evidence = history.associate { it.text to decay(it.weight, it.updatedAt, now) }
        return unique.values.withIndex().sortedByDescending { (index, candidate) ->
            val prior = when {
                index == 0 && baseSize > 0 -> 2.0
                index < baseSize -> 1.0 / (index + 1)
                else -> 0.0
            }
            prior + (evidence[candidate.text] ?: 0.0)
        }.map { it.value }
    }
}

/** 首屏去重/重排后，后续页仍沿用 Rime 的连续原生索引。索引不包含手工候选。 */
internal class CandidateSelection(
    val firstPage: List<RankedCandidate>, private val nativeCount: Int,
    private val excludedTexts: Set<String> = emptySet(),
    private val acceptNative: (String, String) -> Boolean = { _, _ -> true },
) {
    private val followingPages = mutableListOf<RankedCandidate>()
    private var nextNativeIndex = nativeCount

    fun appendNativePage(texts: List<String>, code: String, comments: List<String>? = null): List<Int> {
        val visible = texts.indices.filter {
            isT9CandidateAllowed(code, texts[it]) && acceptNative(texts[it], comments?.getOrNull(it).orEmpty()) &&
                (comments?.getOrNull(it)?.let { reading -> T9Spelling.allows(code, texts[it], reading) }
                    ?: if (comments == null) texts[it] !in excludedTexts else T9Spelling.allows(code, texts[it], ""))
        }
        visible.forEach { index ->
            followingPages.add(RankedCandidate(texts[index], comments?.getOrNull(index).orEmpty(), nativeIndex = nextNativeIndex + index))
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
