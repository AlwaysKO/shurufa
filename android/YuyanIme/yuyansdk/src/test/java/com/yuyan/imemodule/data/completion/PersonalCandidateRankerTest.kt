package com.yuyan.imemodule.data.completion

import org.junit.Assert.*
import org.junit.Test

class PersonalCandidateRankerTest {
    private val base = listOf(RankedCandidate("续期", nativeIndex = 0), RankedCandidate("需求", nativeIndex = 1))
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
