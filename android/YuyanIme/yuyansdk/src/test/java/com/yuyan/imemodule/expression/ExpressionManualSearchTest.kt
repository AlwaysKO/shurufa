package com.yuyan.imemodule.expression

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpressionManualSearchTest {
    @Test
    fun `决策按当前组合最近提交和面板查询的顺序取非空文字`() {
        assertSame(
            ExpressionManualSearchDecision.MissingText,
            ExpressionManualSearch.resolve("  ", null, null),
        )
        assertEquals(
            ExpressionManualSearchDecision.Query("民营企业"),
            ExpressionManualSearch.resolve(null, " 民营企业 ", null),
        )
        assertEquals(
            ExpressionManualSearchDecision.Query("新文字"),
            ExpressionManualSearch.resolve(" 新文字 ", "旧文字", "更旧文字"),
        )
        assertEquals(
            ExpressionManualSearchDecision.Query("面板文字"),
            ExpressionManualSearch.resolve(" ", null, " 面板文字 "),
        )
    }

    @Test
    fun `缺少文字只提示且不打开面板不启用AI也不搜索`() {
        val events = mutableListOf<String>()
        val search = ExpressionManualSearch(
            showMissingText = { events += "toast" },
            preparePanel = { events += "panel" },
            searchImmediately = { events += "search:$it" },
        )

        val decision = search.perform(activeComposingText = " \n", panelLastQuery = null)

        assertSame(ExpressionManualSearchDecision.MissingText, decision)
        assertEquals(listOf("toast"), events)
    }

    @Test
    fun `有文字先准备面板再立即搜索且新组合优先最近提交`() {
        val events = mutableListOf<String>()
        val search = ExpressionManualSearch(
            showMissingText = { events += "toast" },
            preparePanel = { events += "panel" },
            searchImmediately = { events += "search:$it" },
        )
        search.onCommitted(" 旧文字 ")

        val decision = search.perform(activeComposingText = " 新文字 ", panelLastQuery = "更旧文字")

        assertEquals(ExpressionManualSearchDecision.Query("新文字"), decision)
        assertEquals(listOf("panel", "search:新文字"), events)
    }

    @Test
    fun `切换输入会话后不复用旧提交但仍可退回当前面板查询`() {
        val queries = mutableListOf<String>()
        var missingCount = 0
        val search = ExpressionManualSearch(
            showMissingText = { missingCount += 1 },
            preparePanel = {},
            searchImmediately = { queries += it },
        )
        search.onCommitted("旧输入框文字")
        search.resetSession()

        assertSame(
            ExpressionManualSearchDecision.MissingText,
            search.perform(activeComposingText = null, panelLastQuery = null),
        )
        assertEquals(
            ExpressionManualSearchDecision.Query("当前面板文字"),
            search.perform(activeComposingText = null, panelLastQuery = "当前面板文字"),
        )
        assertEquals(1, missingCount)
        assertEquals(listOf("当前面板文字"), queries)
        assertTrue(queries.none { it == "旧输入框文字" })
    }
}
