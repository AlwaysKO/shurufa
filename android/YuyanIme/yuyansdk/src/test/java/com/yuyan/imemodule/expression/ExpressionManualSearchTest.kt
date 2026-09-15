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

    @Test
    fun `纯标点不是有效搜索文字但中文单字是有效意图`() {
        val queries = mutableListOf<String>()
        var missing = 0
        val search = ExpressionManualSearch(
            showMissingText = { missing += 1 },
            preparePanel = {},
            searchImmediately = { queries += it },
        )

        search.onCommitted("！？……")
        assertSame(
            ExpressionManualSearchDecision.MissingText,
            search.perform(activeComposingText = null, panelLastQuery = null),
        )

        search.onCommitted("猫")
        assertEquals(
            ExpressionManualSearchDecision.Query("猫"),
            search.perform(activeComposingText = null, panelLastQuery = null),
        )
        assertEquals(1, missing)
        assertEquals(listOf("猫"), queries)
    }

    @Test
    fun `英文直输的成功逐字提交累积为完整查询`() {
        val search = newSearch()

        "hello".forEach { character ->
            search.onHostCommitted(character.toString(), ExpressionCommitKind.INCREMENTAL)
        }

        assertEquals(
            ExpressionManualSearchDecision.Query("hello"),
            search.perform(activeComposingText = null, panelLastQuery = null),
        )
    }

    @Test
    fun `英文直输空格分隔短语而纯标点不覆盖有效查询`() {
        val search = newSearch()
        "hello world".forEach { character ->
            search.onHostCommitted(character.toString(), ExpressionCommitKind.INCREMENTAL)
        }
        search.onHostCommitted("!?", ExpressionCommitKind.COMPLETE)

        assertEquals(
            ExpressionManualSearchDecision.Query("hello world"),
            search.perform(activeComposingText = null, panelLastQuery = null),
        )

        search.onHostCommitted("w", ExpressionCommitKind.INCREMENTAL)
        assertEquals(
            ExpressionManualSearchDecision.Query("w"),
            search.perform(activeComposingText = null, panelLastQuery = null),
        )
    }

    @Test
    fun `候选或语音整段提交替换逐字累积文字`() {
        val search = newSearch()
        "hello".forEach { character ->
            search.onHostCommitted(character.toString(), ExpressionCommitKind.INCREMENTAL)
        }

        search.onHostCommitted("语音输入整段", ExpressionCommitKind.COMPLETE)

        assertEquals(
            ExpressionManualSearchDecision.Query("语音输入整段"),
            search.perform(activeComposingText = null, panelLastQuery = null),
        )
    }

    @Test
    fun `有效查询按Unicode code point识别补充平面字母`() {
        val supplementaryLetter = "\uD801\uDC00" // U+10400 DESERET CAPITAL LETTER LONG I

        assertEquals(
            ExpressionManualSearchDecision.Query(supplementaryLetter),
            ExpressionManualSearch.resolve(null, supplementaryLetter, null),
        )
    }

    @Test
    fun `无法确定光标末尾时编辑使逐字缓存失效而不拼旧串`() {
        val search = newSearch()
        "hellp".forEach { search.onHostCommitted(it.toString(), ExpressionCommitKind.INCREMENTAL) }

        search.invalidateCommittedText()
        search.onHostCommitted("o", ExpressionCommitKind.INCREMENTAL)

        assertEquals(
            ExpressionManualSearchDecision.Query("o"),
            search.perform(activeComposingText = null, panelLastQuery = null),
        )
    }

    @Test
    fun `连续中文上屏保留本会话短句而不只搜索最后语气词`() {
        val search = newSearch()
        assertEquals("谢谢", search.onHostCommitted("谢谢", ExpressionCommitKind.COMPLETE))
        assertEquals("谢谢啦", search.onHostCommitted("啦", ExpressionCommitKind.COMPLETE))
        search.resetSession()
        assertEquals("我", search.onHostCommitted("我", ExpressionCommitKind.COMPLETE))
        assertEquals("我打", search.onHostCommitted("打", ExpressionCommitKind.COMPLETE))
        assertEquals("我打你", search.onHostCommitted("你", ExpressionCommitKind.COMPLETE))
    }

    @Test
    fun `真实重复中文提交保留重复字而完整英文不重复拼接`() {
        val search = newSearch()
        search.onHostCommitted("哈", ExpressionCommitKind.COMPLETE)
        assertEquals("哈哈", search.onHostCommitted("哈", ExpressionCommitKind.COMPLETE))
        assertEquals("hello", search.onHostCommitted("hello", ExpressionCommitKind.COMPLETE))
        assertEquals(null, search.onHostCommitted("hello", ExpressionCommitKind.COMPLETE))
    }

    @Test
    fun `标点结束中文续接而下一句不拼上一句`() {
        val search = newSearch()
        search.onHostCommitted("谢谢", ExpressionCommitKind.COMPLETE)
        assertEquals(null, search.onHostCommitted("。", ExpressionCommitKind.COMPLETE))
        assertEquals("我", search.onHostCommitted("我", ExpressionCommitKind.COMPLETE))
        search.onHostCommitted("打你！", ExpressionCommitKind.COMPLETE)
        assertEquals("你好", search.onHostCommitted("你好", ExpressionCommitKind.COMPLETE))
    }

    @Test
    fun `本地输入缓冲最多保留一百字且编辑和会话重置丢弃旧句`() {
        val search = newSearch()
        assertEquals("哈".repeat(100), search.onHostCommitted("哈".repeat(120), ExpressionCommitKind.COMPLETE))
        assertEquals("哈".repeat(99) + "呀", search.onHostCommitted("呀", ExpressionCommitKind.COMPLETE))
        search.invalidateCommittedText()
        assertEquals("啦", search.onHostCommitted("啦", ExpressionCommitKind.COMPLETE))
        search.resetSession()
        assertEquals("呀", search.onHostCommitted("呀", ExpressionCommitKind.COMPLETE))
        search.resetSession()
        repeat(120) { search.onHostCommitted("a", ExpressionCommitKind.INCREMENTAL) }
        assertEquals(ExpressionManualSearchDecision.Query("a".repeat(100)), search.perform(null, null))
    }

    @Test
    fun `语气词带句末标点先形成完整查询再断开下一句`() {
        val search = newSearch()
        search.onHostCommitted("谢谢", ExpressionCommitKind.COMPLETE)
        assertEquals("谢谢啦！", search.onHostCommitted("啦！", ExpressionCommitKind.COMPLETE))
        assertEquals("我", search.onHostCommitted("我", ExpressionCommitKind.COMPLETE))
        assertEquals("我打你哦。", search.onHostCommitted("打你哦。", ExpressionCommitKind.COMPLETE))
        assertEquals("你好", search.onHostCommitted("你好", ExpressionCommitKind.COMPLETE))
    }

    @Test
    fun `扩展汉字查询不超过接口一百UTF16单位且不留下半个代理项`() {
        val han = "\uD840\uDC00" // U+20000
        val search = newSearch()
        assertEquals(han.repeat(50), search.onHostCommitted(han.repeat(60), ExpressionCommitKind.COMPLETE))
        val next = search.onHostCommitted("呀", ExpressionCommitKind.COMPLETE)!!
        assertEquals(han.repeat(49) + "呀", next)
        assertTrue(next.length <= 100)
        assertTrue(!Character.isLowSurrogate(next.first()))
        search.resetSession()
        repeat(60) { search.onHostCommitted(han, ExpressionCommitKind.INCREMENTAL) }
        assertEquals(ExpressionManualSearchDecision.Query(han.repeat(50)), search.perform(null, null))
    }

    private fun newSearch() = ExpressionManualSearch(
        showMissingText = {},
        preparePanel = {},
        searchImmediately = {},
    )
}
