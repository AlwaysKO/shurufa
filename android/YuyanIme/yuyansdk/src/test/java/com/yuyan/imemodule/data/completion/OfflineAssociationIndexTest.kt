package com.yuyan.imemodule.data.completion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

class OfflineAssociationIndexTest {
    private val index = OfflineAssociationIndex.parse(
        StringReader(
            listOf(
                "关山\tP\t难越",
                "关山\tP\t魂梦长",
                "关山难越\tN\t谁悲失路之人",
                "床前\tP\t明月光",
            ).joinToString("\n"),
        ),
    )

    @Test
    fun partialClauseUsesLongestTextSuffix() {
        val result = index.query("前文关山")

        assertEquals(listOf("难越", "魂梦长"), result.partial)
        assertTrue(result.next.isEmpty())
    }

    @Test
    fun completeClauseReturnsNextClause() {
        val result = index.query("关山难越")

        assertEquals(listOf("谁悲失路之人"), result.next)
        assertEquals(false, result.trailingPunctuation)
    }

    @Test
    fun trailingPunctuationDoesNotBreakNextClauseLookup() {
        val result = index.query("关山难越，")

        assertEquals(listOf("谁悲失路之人"), result.next)
        assertEquals(true, result.trailingPunctuation)
    }

    @Test
    fun noMatchingSuffixReturnsEmptyResult() {
        assertEquals(OfflineAssociationQuery.EMPTY, index.query("完全不匹配"))
    }

    @Test fun `已输入部分联想后继续推荐剩余正文`() {
        assertEquals(listOf("越"), index.query("前文关山难").partial)
        assertEquals(listOf("梦长"), index.query("关山魂").partial)
    }

    @Test fun `完整匹配和句间联想不被部分补全覆盖`() {
        assertEquals(listOf("谁悲失路之人"), index.query("关山难越").next)
        assertEquals(OfflineAssociationQuery.EMPTY, index.query("关山难越谁悲"))
        assertEquals(OfflineAssociationQuery.EMPTY, index.query("关山难道"))
    }
}
