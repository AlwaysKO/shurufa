package com.yuyan.imemodule.data.completion

import com.yuyan.inputmethod.RimeEngine
import com.yuyan.inputmethod.core.CandidateListItem
import org.junit.Assert.*
import org.junit.After
import org.junit.Test

class CandidateCommitDiagnosticTest {
    @After fun cleanup() = RimeEngine.clearCachedCompositionForSchemaSwitch()

    private fun field(value: Any, name: String): Any? = value.javaClass.getDeclaredField(name).apply {
        isAccessible = true
    }.get(value)

    private fun snapshot(index: Int, code: String): CandidateCommitDiagnostic = RimeEngine.captureCandidateDiagnostic(index, code)

    private fun installCandidates(count: Int = 7, text: String? = null) {
        RimeEngine.clearCachedCompositionForSchemaSwitch()
        val candidates = (0 until count).map { RankedCandidate(text ?: "候选$it", "hou xuan $it", 20 + it) }
        RimeEngine.javaClass.getDeclaredField("nativeCandidateMetadata").apply {
            isAccessible = true
            set(RimeEngine, CandidateSelection(candidates, count))
        }
        RimeEngine.showCandidates = candidates.map { CandidateListItem(it.pinyin, it.text) }
    }

    private fun segment(tracker: T9CommitTracker, code: String, text: String, pinyin: String, committed: String?, diagnostic: Any) {
        tracker.javaClass.methods.single { it.name == "segment" && it.parameterCount == 5 }
            .invoke(tracker, code, text, pinyin, committed, diagnostic)
    }

    @Suppress("UNCHECKED_CAST")
    private fun diagnostics(selection: T9CommitSelection) = field(selection, "diagnostics") as List<Any>

    @Test fun `快照保留重排后前五项与非首屏选中项的绝对位置`() {
        installCandidates()
        val result = snapshot(6, "559")
        val candidates = field(result, "candidates") as List<*>
        assertEquals(5, candidates.size)
        assertEquals("候选0", field(candidates.first()!!, "text"))
        assertEquals(0, field(candidates.first()!!, "index"))
        val selected = field(result, "selected")!!
        assertEquals(6, field(selected, "index"))
        assertEquals(26, field(selected, "nativeIndex"))
        assertEquals("native", field(selected, "source"))
        assertEquals("559", field(result, "code"))
        RimeEngine.showCandidates.first().text = "已经改变"
        assertEquals("候选0", field(candidates.first()!!, "text"))
    }

    @Test fun `本地重排读音从已有元数据获取而不把标签当读音`() {
        RimeEngine.clearCachedCompositionForSchemaSwitch()
        val ranked = CandidateSelection(listOf(RankedCandidate("就可以", "jiu ke yi")), 0)
        RimeEngine.javaClass.getDeclaredField("personalCandidates").apply {
            isAccessible = true
            set(RimeEngine, ranked)
        }
        RimeEngine.showCandidates = listOf(CandidateListItem("本地", "就可以"))
        val selected = field(snapshot(0, "559"), "selected")!!
        assertEquals("jiu ke yi", field(selected, "pinyin"))
        assertEquals("supplemental", field(selected, "source"))
        assertNull(field(selected, "nativeIndex"))
    }

    @Test fun `诊断文字读音和码长有上限`() {
        installCandidates(1, "长".repeat(100))
        RimeEngine.showCandidates.first().comment = "a".repeat(400)
        val result = snapshot(0, "5".repeat(300))
        val candidate = (field(result, "candidates") as List<*>).first()!!
        assertEquals(64, (field(candidate, "text") as String).length)
        assertTrue((field(candidate, "pinyin") as String).length <= 256)
        assertEquals(128, (field(result, "code") as String).length)
    }

    @Test fun `成功整句上屏保留逐段快照并只消费一次`() {
        installCandidates()
        val tracker = T9CommitTracker()
        segment(tracker, "94363362", "真的", "zhen de", null, snapshot(1, "94363362"))
        segment(tracker, "", "吗", "ma", "真的吗", snapshot(2, "62"))
        val result = tracker.consumeSelection("真的吗", true)!!
        assertEquals(2, diagnostics(result).size)
        assertEquals(2, field(result, "diagnosticSelectionCount"))
        assertEquals("94363362", field(diagnostics(result)[0], "code"))
        assertEquals("62", field(diagnostics(result)[1], "code"))
        assertNull(tracker.consumeSelection("真的吗", true))
    }

    @Test fun `取消失败和不匹配的上屏不会把快照串到下一词`() {
        installCandidates()
        val tracker = T9CommitTracker()
        segment(tracker, "62", "吗", "ma", "吗", snapshot(0, "62"))
        assertNull(tracker.consumeSelection("吗", false))
        assertNull(tracker.consumeSelection("吗", true))
        segment(tracker, "62", "吗", "ma", "吗", snapshot(0, "62"))
        assertNull(tracker.consumeSelection("别的", true))
        segment(tracker, "62", "吗", "ma", "吗", snapshot(0, "62"))
        tracker.clear()
        tracker.selected("9436", "真")
        assertTrue(diagnostics(tracker.consumeSelection("真", true)!!).isEmpty())
    }

    @Test fun `过多分段限制快照数量但报告完整段数`() {
        installCandidates()
        val tracker = T9CommitTracker()
        val code = "62".repeat(10)
        repeat(10) { index ->
            segment(tracker, if (index == 0) code else "", "吗", "ma", if (index == 9) "吗".repeat(10) else null, snapshot(0, code))
        }
        val result = tracker.consumeSelection("吗".repeat(10), true)!!
        assertEquals(8, diagnostics(result).size)
        assertEquals(10, field(result, "diagnosticSelectionCount"))
    }
    @Test fun `截断前检查敏感候选且只保留选择位置`() {
        installCandidates(1, "长".repeat(70) + "password")
        val selected = snapshot(0, "559").selected
        assertEquals("", selected.text)
        val selection = T9CommitSelection("559", "就可以", diagnostics = listOf(snapshot(0, "559")), diagnosticSelectionCount = 1)
        val encoded = selection.diagnosticJson().toString()
        assertTrue(encoded.contains("redacted"))
        assertFalse(encoded.contains("password"))
        assertFalse(encoded.contains("长"))
    }

}
