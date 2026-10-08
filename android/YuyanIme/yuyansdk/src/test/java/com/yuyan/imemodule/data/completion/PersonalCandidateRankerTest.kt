package com.yuyan.imemodule.data.completion

import org.junit.Assert.*
import org.junit.Test

class PersonalCandidateRankerTest {
    @Test fun `一次学到的完整组合不能借原生首位压过可信完整词`() {
        val candidates = listOf(
            RankedCandidate("房驾", "fang jia", 7, lexicalEvidence = "personal", wholeInput = true),
            RankedCandidate("房价", "fang jia", 12, lexicalEvidence = "dictionary", wholeInput = true))
        val once = PersonalCandidateRanker.rank(candidates, listOf(ChoiceEvidence("房驾", 1.0, 100, 100)), 100)
        assertEquals(listOf("房价", "房驾"), once.map { it.text })
        assertEquals(listOf(12, 7), once.map { it.nativeIndex })
        val repeated = PersonalCandidateRanker.rank(candidates, listOf(ChoiceEvidence("房驾", 3.0, 100, 100)), 100)
        assertEquals("房驾", repeated.first().text)
    }
    @Test fun `可信分层不让部分单字与未覆盖候选冒充整句证据`() {
        val candidates = listOf(
            RankedCandidate("临时整句", lexicalEvidence = "native_unverified", wholeInput = true),
            RankedCandidate("的", lexicalEvidence = "dictionary"))
        assertEquals(candidates, PersonalCandidateRanker.rank(candidates, emptyList(), 0))
    }
    @Test fun `排序诊断只记录实际已有分数与依据`() {
        val candidates = listOf(RankedCandidate("房价", lexicalEvidence = "dictionary", wholeInput = true),
            RankedCandidate("放假", lexicalEvidence = "dictionary", wholeInput = true))
        val result = PersonalCandidateRanker.rank(candidates, listOf(ChoiceEvidence("放假", 1.0, 100, 100)), 100,
            trace = true, confirmedCorrectionAt = { if (it == "放假") 100 else null })
        assertEquals("放假", result.first().text)
        assertEquals("confirmed_correction", result.first().rankReason)
        assertEquals(3.9, result.first().rankScore!!, 0.0001)
    }
    @Test fun `去重补读音时同步覆盖依据但不同读音不得混合`() {
        val result = PersonalCandidateRanker.rank(listOf(RankedCandidate("房价", nativeIndex = 4),
            RankedCandidate("房价", "fang jia", lexicalEvidence = "dictionary", wholeInput = true)), emptyList(), 0).single()
        assertEquals("fang jia", result.pinyin)
        assertTrue(result.wholeInput)
        assertEquals("dictionary", result.lexicalEvidence)
        assertEquals(4, result.nativeIndex)
        val different = PersonalCandidateRanker.rank(listOf(
            RankedCandidate("银行", "yin hang", lexicalEvidence = "personal"),
            RankedCandidate("银行", "yin xing", lexicalEvidence = "dictionary", wholeInput = true)), emptyList(), 0).single()
        assertEquals("yin hang", different.pinyin)
        assertFalse(different.wholeInput)
        assertEquals("personal", different.lexicalEvidence)
    }
    @Test fun `只有历史召回时没有首项先验`() {
        val result = PersonalCandidateRanker.rank(emptyList(), listOf(ChoiceEvidence("新词", 1.0, 100)), 100, trace = true)
        assertEquals(1.0, result.single().rankScore!!, 0.0)
    }
    @Test fun `稳定学习边界及衰减后不永久占用可信基础先验`() {
        val base = listOf(
            RankedCandidate("房驾", "fang jia", lexicalEvidence = "personal", wholeInput = true),
            RankedCandidate("房价", "fang jia", lexicalEvidence = "dictionary", wholeInput = true))
        fun learnedScore(weight: Double, now: Long) = PersonalCandidateRanker.rank(
            base, listOf(ChoiceEvidence("房驾", weight, 0)), now, trace = true).first { it.text == "房驾" }.rankScore!!
        assertEquals(0.5 + 2.499, learnedScore(2.499, 0), 0.000001)
        assertEquals(2.0 + 2.5, learnedScore(2.5, 0), 0.000001)
        assertEquals(2.0 + 3.0, learnedScore(3.0, 0), 0.000001)
        assertEquals(0.5 + 1.5, learnedScore(3.0, PersonalCandidateRanker.HALF_LIFE_MS), 0.000001)
    }
    private val base = listOf(RankedCandidate("续期", nativeIndex = 0), RankedCandidate("需求", nativeIndex = 1))
    @Test fun `一次近期选择不能无条件压过高频旧词且保留原生索引`() {
        val now = 100000L
        val history = listOf(ChoiceEvidence("续期", 40.0, now - 1000, now - 1000),
            ChoiceEvidence("需求", 1.0, now, now))
        val result = PersonalCandidateRanker.rank(base, history, now)
        assertEquals("续期", result.first().text)
        assertEquals(0, result.first().nativeIndex)
    }

    @Test fun `近期窗口过后恢复衰减频率且未来时间不能置顶`() {
        val selected = 100000L
        val history = listOf(ChoiceEvidence("续期", 40.0, selected - 1000, selected - 1000),
            ChoiceEvidence("需求", 1.0, selected, selected))
        val later = selected + PersonalCandidateRanker.RECENT_CHOICE_MS + 1
        assertEquals("续期", PersonalCandidateRanker.rank(base, history, later).first().text)
        assertEquals(0.0, PersonalCandidateRanker.recentBonus(later + 1, later), 0.0)
        assertEquals(0.0, PersonalCandidateRanker.recentBonus(null, later), 0.0)
    }

    @Test fun `同毫秒的真实选择按原权重稳定排序而非列表偶然顺序`() {
        val history = listOf(ChoiceEvidence("续期", 8.0, 100, 100), ChoiceEvidence("需求", 1.0, 100, 100))
        assertEquals("续期", PersonalCandidateRanker.rank(base, history, 100).first().text)
    }

    @Test fun `有真实选择时间的一次偶选次日不持续抢占默认首位`() {
        val at = 100000L
        val history = listOf(ChoiceEvidence("需求", 1.0, at, at))
        for (now in listOf(at, at + 60_000, at + 20 * 60 * 60_000L)) {
            val result = PersonalCandidateRanker.rank(base, history, now)
            assertEquals("续期", result.first().text)
            assertEquals(setOf("续期", "需求"), result.map { it.text }.toSet())
        }
    }

    @Test fun `近期加分有上限并连续衰减而不是时间优先`() {
        val at = 100000L
        assertEquals(0.4, PersonalCandidateRanker.recentBonus(at, at), 0.000001)
        assertEquals(0.2, PersonalCandidateRanker.recentBonus(at, at + 30 * 60_000L), 0.000001)
        assertEquals(0.1, PersonalCandidateRanker.recentBonus(at, at + 60 * 60_000L), 0.000001)
        assertEquals(0.0, PersonalCandidateRanker.recentBonus(at, at + PersonalCandidateRanker.RECENT_CHOICE_MS + 1), 0.0)
    }

    @Test fun `两次真实选择仍可提升且历史新词没有首项先验`() {
        val history = listOf(ChoiceEvidence("需求", 2.0, 100, 100))
        assertEquals("需求", PersonalCandidateRanker.rank(base, history, 100).first().text)
        val extra = listOf(ChoiceEvidence("新区", 1.0, 100, 100))
        assertEquals(listOf("续期", "新区", "需求"), PersonalCandidateRanker.rank(base, extra, 100).map { it.text })
    }

    @Test fun `聚合计算时间不是实际同码选词时间且已有历史允许合理翻转`() {
        assertEquals("续期", PersonalCandidateRanker.rank(base,
            listOf(ChoiceEvidence("需求", 1.25, 100)), 100).first().text)
        assertEquals("需求", PersonalCandidateRanker.rank(base,
            listOf(ChoiceEvidence("需求", 1.25, 100, 100)), 100).first().text)
    }

    @Test fun `首屏无可见词时预取后续页的首项使用真实原生索引`() {
        val selection = CandidateSelection(emptyList(), 2)
        assertTrue(selection.appendNativePage(listOf("美国最高法院"), "649439").isEmpty())
        assertEquals(listOf(0), selection.appendNativePage(listOf("你这样"), "649439"))
        assertEquals(3, selection.at(0)?.nativeIndex)
    }

    @Test fun `过滤翻页长词后选择索引仍对应原生词且空页不占展示位置`() {
        val selection = CandidateSelection(listOf(RankedCandidate("你这样", nativeIndex = 1)), 2)
        assertEquals(listOf(1), selection.appendNativePage(listOf("美国最高法院", "你这样子"), "649439"))
        assertEquals(3, selection.at(1)?.nativeIndex)
        assertTrue(selection.appendNativePage(listOf("美国最高法院"), "649439").isEmpty())
        assertEquals(listOf(0), selection.appendNativePage(listOf("你好"), "649439"))
        assertEquals(5, selection.at(2)?.nativeIndex)
    }

    @Test fun `无历史保留基础排序且一次误选不压过默认`() {
        assertEquals(base, PersonalCandidateRanker.rank(base, emptyList(), 0))
        assertEquals("续期", PersonalCandidateRanker.rank(base, listOf(ChoiceEvidence("需求", 1.0, 0)), 0).first().text)
    }
    @Test fun `重复选择需求超过续期并保留原始选择索引`() {
        val ranked = PersonalCandidateRanker.rank(base, listOf(ChoiceEvidence("需求", 3.0, 0)), 0)
        assertEquals("需求", ranked.first().text)
        assertEquals(1, ranked.first().nativeIndex)
    }
    @Test fun `旧习惯逐渐衰减而非永久置顶`() {
        assertEquals("续期", PersonalCandidateRanker.rank(base, listOf(ChoiceEvidence("需求", 3.0, 0)), 4 * PersonalCandidateRanker.HALF_LIFE_MS).first().text)
        assertEquals(1.5, PersonalCandidateRanker.decay(3.0, 0, PersonalCandidateRanker.HALF_LIFE_MS), 0.0001)
    }
    @Test fun `同码更高选择占比排前而非所有历史词置顶`() {
        val history = listOf(ChoiceEvidence("续期", 8.0, 0), ChoiceEvidence("需求", 3.0, 0))
        assertEquals("续期", PersonalCandidateRanker.rank(base, history, 0).first().text)
    }
    @Test fun `去重优先保留原生索引以及本地拼音`() {
        val result = PersonalCandidateRanker.rank(listOf(RankedCandidate("需求", "xu qiu"), RankedCandidate("需求", nativeIndex = 7)), emptyList(), 0)
        assertEquals(1, result.size)
        assertEquals(7, result.single().nativeIndex)
        assertEquals("xu qiu", result.single().pinyin)
    }
    @Test fun `历史词不在基础列表也能召回但一次不抢首位`() {
        val result = PersonalCandidateRanker.rank(base, listOf(ChoiceEvidence("新区", 1.0, 0)), 0)
        assertEquals("续期", result.first().text)
        assertTrue(result.any { it.text == "新区" })
    }

    @Test fun `混合重排与去重不破坏翻页的原生索引`() {
        val selection = CandidateSelection(listOf(
            RankedCandidate("需求", nativeIndex = 1),
            RankedCandidate("候选词", "hou xuan ci"),
            RankedCandidate("续期", nativeIndex = 0),
        ), 2)
        assertEquals(1, selection.at(0)?.nativeIndex)
        assertNull(selection.at(1)?.nativeIndex)
        assertEquals(0, selection.at(2)?.nativeIndex)
        assertEquals(2, selection.at(3)?.nativeIndex)
        assertEquals(10, selection.at(11)?.nativeIndex)
        assertNull(selection.at(-1))
    }
}
