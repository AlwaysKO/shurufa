package com.yuyan.inputmethod

import com.yuyan.inputmethod.core.CandidateListItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import com.yuyan.imemodule.data.completion.CandidateSelection
import com.yuyan.imemodule.data.completion.RankedCandidate
import org.junit.Test

class RimeEngineCompositionStateTest {
    @Test fun `分段后原生第二页仍有真实文字读音与索引`() {
        RimeEngine.clearCachedCompositionForSchemaSwitch()
        val history = CandidateSelection(listOf(RankedCandidate("吗", "ma", 0)), 1)
        history.appendNativePage(listOf("嘛"), "", listOf("ma"))
        RimeEngine::class.java.getDeclaredField("nativeCandidateMetadata").apply {
            isAccessible = true
            set(RimeEngine, history)
        }
        assertEquals(RankedCandidate("嘛", "ma", 1), RimeEngine.candidateForSelection(1))
        RimeEngine.clearCachedCompositionForSchemaSwitch()
        assertEquals("", RimeEngine.candidateForSelection(1)?.text.orEmpty())
    }

    @Test
    fun `schema切换会清理展示候选和待上屏缓存`() {
        RimeEngine.showComposition = "old preedit"
        RimeEngine.showCandidates = listOf(CandidateListItem("", "旧候选"))
        RimeEngine.preCommitText = "旧上屏"

        RimeEngine.clearCachedCompositionForSchemaSwitch()

        assertEquals("", RimeEngine.showComposition)
        assertTrue(RimeEngine.showCandidates.isEmpty())
        assertEquals("", RimeEngine.preCommitText)
    }
}
