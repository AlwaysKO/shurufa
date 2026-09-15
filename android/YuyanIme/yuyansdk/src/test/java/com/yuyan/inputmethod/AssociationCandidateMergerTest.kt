package com.yuyan.inputmethod

import com.yuyan.imemodule.data.completion.OfflineAssociationQuery
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
}
