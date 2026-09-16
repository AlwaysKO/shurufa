package com.yuyan.imemodule.expression

import com.yuyan.imemodule.expression.model.ExpressionAsset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpressionPanelStateTest {
    @Test fun `已选推荐被更新为空时不能停留在隐藏标签`() {
        val state = ExpressionPanelState()
        state.beginQuery("谢谢", 1)
        state.applyResults(1, listOf(asset("thanks")))
        state.selectTab(ExpressionPanelTab.RECOMMENDED)
        state.applyResults(1, listOf(asset("blank").copy(type = "synthesis-template", format = "gif")))
        assertTrue(state.results.isEmpty())
        assertEquals(ExpressionPanelTab.AI_SYNTHESIS, state.selectedTab)
    }

    @Test fun `自动空回调后命中仍显示推荐且自动合成兜底可被成品替代`() {
        val state = ExpressionPanelState()
        state.beginQuery("谢谢", 1)
        state.applyResults(1, emptyList())
        assertEquals(ExpressionPanelTab.RECOMMENDED, state.selectedTab)
        state.applyResults(1, listOf(asset("blank").copy(type = "synthesis-template", format = "gif")))
        assertEquals(ExpressionPanelTab.AI_SYNTHESIS, state.selectedTab)
        state.applyResults(1, listOf(asset("thanks")))
        assertEquals(ExpressionPanelTab.RECOMMENDED, state.selectedTab)
        state.selectTab(ExpressionPanelTab.AI_SYNTHESIS)
        state.applyResults(1, listOf(asset("thanks-late")))
        assertEquals(ExpressionPanelTab.AI_SYNTHESIS, state.selectedTab)
    }

    @Test fun `自动合成结果不能冒充推荐图且直接选择AI合成`() {
        val state = ExpressionPanelState()
        state.beginQuery("笑死", 1)
        state.applyResults(1, listOf(asset("blank-cat").copy(type = "synthesis-template", format = "gif")))
        assertTrue(state.results.isEmpty())
        assertTrue(state.isContentVisible)
        assertEquals(ExpressionPanelTab.AI_SYNTHESIS, state.selectedTab)
    }

    @Test fun `手动搜索无推荐时仍可选择合成模板而自动搜索保持隐藏`() {
        val state = ExpressionPanelState()
        state.beginQuery("机构", 1, manual = true)
        state.applyResults(1, emptyList())
        assertTrue(state.isContentVisible)
        assertEquals(ExpressionPanelTab.AI_SYNTHESIS, state.selectedTab)
        assertTrue(state.results.isEmpty())
        state.beginQuery("编制", 2)
        state.applyResults(2, emptyList())
        assertFalse(state.isContentVisible)
    }

    @Test
    fun `非聊天输入框隐藏斗图按钮且拒绝推荐结果`() {
        val state = ExpressionPanelState(chatEditor = false)

        state.beginQuery("你好", requestId = 1)

        assertFalse(state.applyResults(1, listOf(asset("hello"))))
        assertFalse(state.isRecommendationActionVisible)
        assertFalse(state.isRecommendationVisible)
        assertTrue(state.results.isEmpty())
    }

    @Test
    fun `切换输入目标清空瞬态推荐但保留用户开关`() {
        val state = ExpressionPanelState(aiStickerEnabled = true, chatEditor = true)
        state.beginQuery("你好", requestId = 1)
        state.applyResults(1, listOf(asset("hello")))
        state.expand()

        state.setChatEditor(false)

        assertTrue(state.aiStickerEnabled)
        assertFalse(state.chatEditor)
        assertEquals(null, state.query)
        assertTrue(state.results.isEmpty())
        assertFalse(state.isRecommendationVisible)
        assertEquals(ExpressionPanelPresentation.COMPACT, state.presentation)
        assertFalse(state.applyResults(1, listOf(asset("stale"))))

        state.setChatEditor(true)
        state.beginQuery("你好", requestId = 2)
        assertTrue(state.applyResults(2, listOf(asset("fresh"))))
        assertTrue(state.isRecommendationVisible)
    }

    @Test
    fun `无结果隐藏按钮而有结果时打开推荐内容`() {
        val state = ExpressionPanelState()

        state.beginQuery("开心", requestId = 1)
        state.applyResults(requestId = 1, results = emptyList())
        assertFalse(state.isVisible)
        assertFalse(state.isContentVisible)

        state.applyResults(requestId = 1, results = listOf(asset("happy")))
        assertTrue(state.isContentVisible)
        assertTrue(state.isRecommendationActionVisible)
        assertTrue(state.isVisible)
        assertEquals(ExpressionPanelTab.RECOMMENDED, state.selectedTab)
        assertEquals(ExpressionPanelPresentation.COMPACT, state.presentation)
    }

    @Test
    fun `用户关闭后清空查询并拒绝旧结果直到下一次提交`() {
        val state = ExpressionPanelState()
        state.beginQuery("开心", requestId = 1)
        state.applyResults(1, listOf(asset("happy")))

        state.setAiStickerEnabled(false)
        assertFalse(state.applyResults(1, listOf(asset("remote-happy"))))
        assertFalse(state.isVisible)
        assertFalse(state.isContentVisible)
        assertEquals(null, state.query)
        assertTrue(state.results.isEmpty())

        state.setAiStickerEnabled(true)
        assertFalse(state.isContentVisible)

        state.beginQuery("开心", requestId = 2)
        state.applyResults(2, listOf(asset("fresh-happy")))
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

        assertFalse(state.isVisible)
        assertFalse(state.isContentVisible)
        assertTrue(state.results.isEmpty())
        assertEquals(null, state.query)
        assertEquals(ExpressionPanelTab.RECOMMENDED, state.selectedTab)
        assertEquals(ExpressionPanelPresentation.COMPACT, state.presentation)
        assertTrue(state.keyboardVisible)
    }


    @Test
    fun `临时关闭推荐保留查询结果并可原样恢复`() {
        val state = ExpressionPanelState()
        state.beginQuery("民营企业", requestId = 12)
        val original = listOf(asset("enterprise"))
        state.applyResults(12, original)
        state.selectTab(ExpressionPanelTab.AI_SYNTHESIS)

        state.hideRecommendations()

        assertFalse(state.isRecommendationVisible)
        assertTrue(state.recommendationsPaused)
        assertFalse(state.acceptResponse(12))
        assertEquals("民营企业", state.query)
        assertEquals(original, state.results)
        assertEquals(ExpressionPanelTab.AI_SYNTHESIS, state.selectedTab)

        state.restoreRecommendations()

        assertFalse(state.recommendationsPaused)
        assertTrue(state.isRecommendationVisible)
        assertEquals("民营企业", state.query)
        assertEquals(original, state.results)
        assertEquals(ExpressionPanelTab.AI_SYNTHESIS, state.selectedTab)
    }

    @Test
    fun `无结果时手动入口也能先恢复推荐再开始新请求`() {
        val state = ExpressionPanelState()
        state.beginQuery("旧词", 20)
        state.hideRecommendations()

        state.restoreRecommendations()
        state.beginQuery("新词", 21)

        assertFalse(state.recommendationsPaused)
        assertTrue(state.acceptResponse(21))
        assertEquals("新词", state.query)
    }

    private fun asset(id: String) = ExpressionAsset(
        id = id,
        type = "prebuilt",
        format = "webp",
        version = "v1",
        fileName = "templates/$id.webp",
        sha256 = id.padEnd(64, 'a').take(64),
        width = 512,
        height = 512,
    )
}
