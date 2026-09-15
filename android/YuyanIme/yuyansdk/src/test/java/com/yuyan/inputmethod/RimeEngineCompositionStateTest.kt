package com.yuyan.inputmethod

import com.yuyan.inputmethod.core.CandidateListItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RimeEngineCompositionStateTest {
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
