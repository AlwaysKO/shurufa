package com.yuyan.imemodule.expression

import com.yuyan.imemodule.expression.model.ExpressionAsset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpressionPanelStateTest {
    @Test
    fun `无结果隐藏而有结果默认打开推荐标签`() {
        val state = ExpressionPanelState()

        state.beginQuery("开心", requestId = 1)
        state.applyResults(requestId = 1, results = emptyList())
        assertFalse(state.isVisible)

        state.applyResults(requestId = 1, results = listOf(asset("happy")))
        assertTrue(state.isVisible)
        assertEquals(ExpressionPanelTab.RECOMMENDED, state.selectedTab)
    }

    @Test
    fun `用户收起后同一查询不自动重开而新查询可以重开`() {
        val state = ExpressionPanelState()
        state.beginQuery("开心", requestId = 1)
        state.applyResults(1, listOf(asset("happy")))

        state.dismiss()
        state.applyResults(1, listOf(asset("remote-happy")))
        assertFalse(state.isVisible)

        state.beginQuery("生气", requestId = 2)
        state.applyResults(2, listOf(asset("angry")))
        assertTrue(state.isVisible)
    }

    @Test
    fun `切换标签保留当前查询`() {
        val state = ExpressionPanelState()
        state.beginQuery("放箭", requestId = 4)
        state.applyResults(4, listOf(asset("arrow")))

        state.selectTab(ExpressionPanelTab.TEMPLATES)

        assertEquals(ExpressionPanelTab.TEMPLATES, state.selectedTab)
        assertEquals("放箭", state.query)
    }

    @Test
    fun `旧请求不能更新当前结果和可见性`() {
        val state = ExpressionPanelState()
        state.beginQuery("旧词", requestId = 7)
        state.beginQuery("新词", requestId = 8)

        assertFalse(state.applyResults(7, listOf(asset("stale"))))
        assertTrue(state.results.isEmpty())
        assertFalse(state.isVisible)

        assertTrue(state.applyResults(8, listOf(asset("fresh"))))
        assertEquals(listOf("fresh"), state.results.map { it.id })
    }

    private fun asset(id: String) = ExpressionAsset(
        id = id,
        type = "template",
        format = "webp",
        version = "v1",
        fileName = "templates/$id.webp",
        sha256 = id.padEnd(64, 'a').take(64),
        width = 512,
        height = 512,
    )
}
