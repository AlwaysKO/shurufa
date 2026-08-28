package com.yuyan.imemodule.expression

import com.yuyan.imemodule.service.DecodingInfo
import com.yuyan.inputmethod.RimeEngine
import com.yuyan.inputmethod.core.CandidateListItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExpressionComposingTextSourceTest {
    @After
    fun tearDown() {
        RimeEngine.showComposition = ""
        DecodingInfo.candidatesLiveData.value = emptyList()
        DecodingInfo.isAssociate = false
    }

    @Test
    fun `只有引擎组合编码非空时才取当前候选文字`() {
        var composition = ""
        var candidate = "民营企业"
        val source = ExpressionComposingTextSource(
            compositionText = { composition },
            isAssociate = { false },
            candidateText = { candidate },
        )

        assertNull(source.currentText(activeCandidateIndex = 0))

        composition = "min'ying'qi'ye"
        assertEquals("民营企业", source.currentText(activeCandidateIndex = 0))

        candidate = "  "
        assertNull(source.currentText(activeCandidateIndex = 0))
    }

    @Test
    fun `联想候选和过期候选不能冒充当前组合文字`() {
        var associate = true
        var composition: String? = "nihao"
        val source = ExpressionComposingTextSource(
            compositionText = { composition },
            isAssociate = { associate },
            candidateText = { "你好" },
        )

        assertNull(source.currentText(activeCandidateIndex = 0))

        associate = false
        composition = null
        assertNull(source.currentText(activeCandidateIndex = 0))
    }

    @Test
    fun `激活候选不存在时回退首候选但仍需组合态门控`() {
        val candidates = mapOf(0 to "首候选")
        val source = ExpressionComposingTextSource(
            compositionText = { "shouhouxuan" },
            isAssociate = { false },
            candidateText = candidates::get,
        )

        assertEquals("首候选", source.currentText(activeCandidateIndex = -1))
    }

    @Test
    fun `生产引擎源使用Rime组合编码门控DecodingInfo当前候选`() {
        val source = ExpressionComposingTextSource.fromEngine()
        DecodingInfo.cacheCandidates(arrayOf(CandidateListItem("", "真实候选")))

        RimeEngine.showComposition = ""
        assertNull(source.currentText(activeCandidateIndex = 0))

        RimeEngine.showComposition = "zhen'shi"
        assertEquals("真实候选", source.currentText(activeCandidateIndex = 0))
    }
}
