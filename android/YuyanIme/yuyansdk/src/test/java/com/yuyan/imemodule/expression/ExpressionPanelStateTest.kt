package com.yuyan.imemodule.expression

import com.yuyan.imemodule.expression.model.ExpressionAsset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpressionPanelStateTest {
    @Test
    fun `工具行始终可见而有结果时打开推荐内容`() {
        val state = ExpressionPanelState()

        state.beginQuery("开心", requestId = 1)
        state.applyResults(requestId = 1, results = emptyList())
        assertTrue(state.isVisible)
        assertFalse(state.isContentVisible)

        state.applyResults(requestId = 1, results = listOf(asset("happy")))
        assertTrue(state.isContentVisible)
        assertEquals(ExpressionPanelTab.RECOMMENDED, state.selectedTab)
        assertEquals(ExpressionPanelPresentation.COMPACT, state.presentation)
    }

    @Test
    fun `用户收起后同一查询不自动重开而新查询可以重开`() {
        val state = ExpressionPanelState()
        state.beginQuery("开心", requestId = 1)
        state.applyResults(1, listOf(asset("happy")))

        state.setAiStickerEnabled(false)
        state.applyResults(1, listOf(asset("remote-happy")))
        assertTrue(state.isVisible)
        assertFalse(state.isContentVisible)

        state.setAiStickerEnabled(true)
        assertTrue(state.isContentVisible)
    }

    @Test
    fun `切换标签保留当前查询且不自动展开`() {
        val state = ExpressionPanelState()
        state.beginQuery("放箭", requestId = 4)
        state.applyResults(4, listOf(asset("arrow")))

        state.selectTab(ExpressionPanelTab.AI_SYNTHESIS)

        assertEquals(ExpressionPanelTab.AI_SYNTHESIS, state.selectedTab)
        assertEquals("放箭", state.query)
        assertEquals(ExpressionPanelPresentation.COMPACT, state.presentation)
    }

    @Test
    fun `长按展开时收起键盘而退出后恢复`() {
        val state = ExpressionPanelState()
        state.beginQuery("放箭", requestId = 4)
        state.applyResults(4, listOf(asset("arrow")))

        state.expand()
        assertEquals(ExpressionPanelPresentation.EXPANDED, state.presentation)
        assertFalse(state.keyboardVisible)

        state.collapse()
        assertEquals(ExpressionPanelPresentation.COMPACT, state.presentation)
        assertTrue(state.keyboardVisible)
    }

    @Test
    fun `旧请求不能更新当前结果和可见性`() {
        val state = ExpressionPanelState()
        state.beginQuery("旧词", requestId = 7)
        state.beginQuery("新词", requestId = 8)

        assertFalse(state.applyResults(7, listOf(asset("stale"))))
        assertTrue(state.results.isEmpty())
        assertFalse(state.isContentVisible)

        assertTrue(state.applyResults(8, listOf(asset("fresh"))))
        assertEquals(listOf("fresh"), state.results.map { it.id })
    }

    @Test
    fun `候选清空时移除旧查询和图片结果`() {
        val state = ExpressionPanelState()
        state.beginQuery("放箭", requestId = 1)
        state.applyResults(1, listOf(asset("arrow")))

        state.clear()

        assertTrue(state.isVisible)
        assertFalse(state.isContentVisible)
        assertTrue(state.results.isEmpty())
        assertEquals(null, state.query)
        assertEquals(ExpressionPanelTab.RECOMMENDED, state.selectedTab)
        assertEquals(ExpressionPanelPresentation.COMPACT, state.presentation)
        assertTrue(state.keyboardVisible)
    }

    private fun asset(id: String) = ExpressionAsset(
        id = id,
        type = "synthesis-template",
        format = "webp",
        version = "v1",
        fileName = "templates/$id.webp",
        sha256 = id.padEnd(64, 'a').take(64),
        width = 512,
        height = 512,
    )
}
