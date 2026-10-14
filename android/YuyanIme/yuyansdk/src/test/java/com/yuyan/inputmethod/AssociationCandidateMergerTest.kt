package com.yuyan.inputmethod

import com.yuyan.imemodule.data.completion.OfflineAssociationQuery
import com.yuyan.imemodule.data.completion.OfflineAssociationIndex
import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AssociationCandidateMergerTest {
    @Test
    fun partialClassicalCompletionIsFirstAndDeduplicated() {
        val merged = AssociationCandidateMerger.merge(
            offline = OfflineAssociationQuery(listOf("难越"), emptyList(), false),
            rime = listOf("关山月", "难越"),
            custom = listOf("，", "。"),
            remote = emptyList(),
        )

        assertEquals(listOf("难越", "关山月", "，", "。"), merged.map { it.text })
        assertNull(merged.first().rimeIndex)
        assertEquals(0, merged[1].rimeIndex)
    }

    @Test
    fun punctuationPrecedesNextClauseWhenItHasNotBeenTyped() {
        val merged = AssociationCandidateMerger.merge(
            offline = OfflineAssociationQuery(emptyList(), listOf("谁悲失路之人"), false),
            rime = emptyList(),
            custom = listOf("，", "。"),
            remote = emptyList(),
        )

        assertEquals(listOf("，", "。", "谁悲失路之人"), merged.map { it.text })
    }

    @Test
    fun nextClauseIsFirstAfterPunctuationHasBeenTyped() {
        val merged = AssociationCandidateMerger.merge(
            offline = OfflineAssociationQuery(emptyList(), listOf("谁悲失路之人"), true),
            rime = listOf("其他"),
            custom = listOf("，", "。"),
            remote = emptyList(),
        )

        assertEquals("谁悲失路之人", merged.first().text)
    }

    @Test
    fun nativeRimeIndexSurvivesCandidatesInsertedBeforeIt() {
        val merged = AssociationCandidateMerger.merge(
            offline = OfflineAssociationQuery(listOf("休息"), emptyList(), false),
            rime = listOf("回来", "睡觉"),
            custom = emptyList(),
            remote = emptyList(),
        )

        assertEquals(listOf(null, 0, 1), merged.map { it.rimeIndex })
    }
    @Test fun ambiguousLiteraryPrefixKeepsNativeDailyCandidatesFirst() {
        val offline = OfflineAssociationIndex.parse(StringReader("今天\tP\t下之人\n今天\tN\t古文下一句")).query("今天")
        val merged = AssociationCandidateMerger.merge(offline, listOf("晚上", "下午", "有空吗"), listOf("，"), emptyList())
        assertEquals(listOf("晚上", "下午", "有空吗", "，", "下之人", "古文下一句"), merged.map { it.text })
        assertEquals(listOf(0, 1, 2), merged.take(3).map { it.rimeIndex })
    }

    @Test fun longerLiteraryMatchStillCompletesBeforeNativeCandidates() {
        val offline = OfflineAssociationIndex.parse(StringReader("床前\tP\t明月光")).query("床前明")
        val merged = AssociationCandidateMerger.merge(offline, listOf("其他"), emptyList(), emptyList())
        assertEquals("月光", merged.first().text)
    }

    @Test fun dailySourceWinsWithoutDiscardingLiteratureOrNativeIndices() {
        val offline = OfflineAssociationIndex.parse(StringReader("今天\tP\t下之人\n今天\tD\t晚上")).query("今天")
        val merged = AssociationCandidateMerger.merge(offline, listOf("下午", "下之人"), emptyList(), emptyList())
        assertEquals(listOf("晚上", "下午", "下之人"), merged.map { it.text })
        assertEquals(1, merged.last().rimeIndex)
    }

}
