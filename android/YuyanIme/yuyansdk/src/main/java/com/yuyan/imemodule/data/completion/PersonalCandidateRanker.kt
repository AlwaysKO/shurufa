package com.yuyan.imemodule.data.completion

import kotlin.math.pow
import com.yuyan.inputmethod.util.T9Spelling

internal data class ChoiceEvidence(val text: String, val weight: Double, val updatedAt: Long, val lastSelectedAt: Long? = null)
internal data class RankedCandidate(
    val text: String, val pinyin: String = "", val nativeIndex: Int? = null,
    val inputMatch: InputSpellingMatch? = null,
    // 表示应用实际掌握的依据，不声称知道原生引擎内部词条来源。
    val lexicalEvidence: String = "unknown", val wholeInput: Boolean = false,
    val rankScore: Double? = null, val rankReason: String? = null,
)

/** 编码内排序：基础先验 + 衰减选中次数 + 有界近期加分，不以一次选择时间强制置顶。 */
internal object PersonalCandidateRanker {
    const val HALF_LIFE_MS = 14L * 24 * 60 * 60 * 1000
    const val RECENT_CHOICE_MS = 24L * 60 * 60 * 1000

    private const val RECENT_HALF_LIFE_MS = 30L * 60 * 1000
    private const val MAX_RECENT_BONUS = 0.4
    private val establishedEvidence = setOf("dictionary", "public_phrase", "personal_preferred")

    /** 只接收真实同码选择时间，聚合权重计算时间不能冒充最近选词。 */
    fun recentBonus(lastSelectedAt: Long?, now: Long): Double {
        if (lastSelectedAt == null || lastSelectedAt > now) return 0.0
        val elapsed = now - lastSelectedAt
        if (elapsed < 0 || elapsed > RECENT_CHOICE_MS) return 0.0
        return MAX_RECENT_BONUS * 0.5.pow(elapsed.toDouble() / RECENT_HALF_LIFE_MS)
    }

    fun correctionBonus(confirmedAt: Long?, now: Long): Double = recentBonus(confirmedAt, now) * 5.0

    /** baseIndex 是基础列表位置而不是原生选择索引；仅历史召回项传 null。 */
    fun score(baseIndex: Int?, weight: Double, updatedAt: Long, lastSelectedAt: Long?, now: Long, confirmedCorrectionAt: Long? = null): Double {
        val prior = when (baseIndex) {
            null -> 0.0
            0 -> 2.0
            else -> 1.0 / (baseIndex + 1)
        }
        return prior + decay(weight, updatedAt, now) + recentBonus(lastSelectedAt, now) + correctionBonus(confirmedCorrectionAt, now)
    }

    fun decay(weight: Double, updatedAt: Long, now: Long): Double =
        weight * 0.5.pow((now - updatedAt).coerceAtLeast(0).toDouble() / HALF_LIFE_MS)

    fun rank(base: List<RankedCandidate>, history: List<ChoiceEvidence>, now: Long, trace: Boolean = false, confirmedCorrectionAt: (String) -> Long? = { null }): List<RankedCandidate> {
        val unique = linkedMapOf<String, RankedCandidate>()
        base.forEach { candidate ->
            val previous = unique[candidate.text]
            unique[candidate.text] = if (previous == null) candidate else previous.copy(
                nativeIndex = previous.nativeIndex ?: candidate.nativeIndex,
                pinyin = previous.pinyin.ifBlank { candidate.pinyin },
                inputMatch = previous.inputMatch ?: candidate.inputMatch,
                lexicalEvidence = if (previous.pinyin.isBlank() && candidate.pinyin.isNotBlank()) candidate.lexicalEvidence else previous.lexicalEvidence,
                wholeInput = if (previous.pinyin.isBlank() && candidate.pinyin.isNotBlank()) candidate.wholeInput else previous.wholeInput,
            )
        }
        val baseSize = unique.size
        history.forEach { unique.putIfAbsent(it.text, RankedCandidate(it.text)) }
        val evidence = history.associateBy { it.text }
        // 只在完整覆盖的多字项之间交换基础位置；不拿前缀单字或预测后缀当整词竞争者。
        val items = unique.values.toMutableList()
        val slots = items.indices.filter { items[it].wholeInput }
        val whole = slots.map { items[it] }
        fun established(candidate: RankedCandidate): Boolean =
            candidate.lexicalEvidence in establishedEvidence ||
                evidence[candidate.text]?.let { decay(it.weight, it.updatedAt, now) >= 2.5 } == true
        val (trusted, unverified) = whole.partition(::established)
        val ordered = trusted + unverified
        slots.forEachIndexed { index, slot -> items[slot] = ordered[index] }
        // 先算一次分数，再排序；不能在比较器内重复查询提示或计算衰减。
        return items.mapIndexed { index, candidate ->
            val choice = evidence[candidate.text]
            val confirmed = confirmedCorrectionAt(candidate.text)
            val score = score(index.takeIf { it < baseSize }, choice?.weight ?: 0.0,
                choice?.updatedAt ?: now, choice?.lastSelectedAt, now, confirmed)
            val value = if (!trace) candidate else candidate.copy(rankScore = score,
                rankReason = when {
                    correctionBonus(confirmed, now) > 0 -> "confirmed_correction"
                    choice != null && choice.weight > 0 -> "base_and_learning"
                    else -> "base"
                })
            score to value
        }.sortedByDescending { it.first }.map { it.second }
    }
}

/** 首屏去重/重排后，后续页仍沿用 Rime 的连续原生索引。索引不包含手工候选。 */
internal class CandidateSelection(
    firstPage: List<RankedCandidate>, private val nativeCount: Int,
    private val excludedTexts: Set<String> = emptySet(),
    private val extraMatch: (String, String) -> InputSpellingMatch? = { _, _ -> null },
    private val blockedTexts: Set<String> = emptySet(),
    private val acceptNative: (String, String) -> Boolean = { _, _ -> true },
) {
    val firstPage = if (blockedTexts.isEmpty()) firstPage else firstPage.filterNot { it.text in blockedTexts }
    private val followingPages = mutableListOf<RankedCandidate>()
    private var nextNativeIndex = nativeCount

    fun appendNativePage(texts: List<String>, code: String, comments: List<String>? = null, latestBlockedTexts: Set<String> = emptySet()): List<Int> {
        val visible = texts.indices.filter {
            if (texts[it] in blockedTexts || texts[it] in latestBlockedTexts) return@filter false
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
