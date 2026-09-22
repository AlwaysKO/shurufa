package com.yuyan.imemodule.data.completion

import org.junit.Assert.*
import org.junit.Test

class CorrectionLearningTrackerTest {
    private fun start() = CorrectionLearningTracker().apply {
        commit("3264542", "房价", "前后", "前房价后", "reward", 1000)
    }
    @Test fun `严格同码原位换词返回原奖励且只能消费一次`() {
        val t = start()
        t.delete("前房价后", "前房后", 2000)
        t.delete("前房后", "前后", 2100)
        assertEquals("reward", t.commit("3264542", "放假", "前后", "前放假后", "new", 3000))
        assertNull(t.commit("3264542", "放假", "前后", "前放假后", "new", 3001))
    }
    @Test fun `键盘完整删空允许纠错但宿主清空重置不允许`() {
        val t = CorrectionLearningTracker()
        t.commit("3", "得", "", "得", "reward", 0)
        t.delete("得", "", 2000)
        assertEquals("reward", t.commit("3", "的", "", "的", "new", 17000))
        t.reset()
        assertNull(t.commit("3", "得", "", "得", "next", 17001))
    }
    @Test fun `晚删超时部分删同词异码异位快照缺失均不撤销`() {
        val cases = listOf(
            Triple(3001L, 4000L, "late-delete"), Triple(2000L, 17001L, "late-replace"),
            Triple(2000L, 3000L, "partial"), Triple(2000L, 3000L, "same"),
            Triple(2000L, 3000L, "code"), Triple(2000L, 3000L, "position"),
            Triple(2000L, 3000L, "missing"), Triple(2000L, 3000L, "broken"),
        )
        for ((deletedAt, replacedAt, kind) in cases) {
            val t = start()
            val base = if (kind == "partial") "前房后" else "前后"
            t.delete(if (kind == "broken") "别房价后" else "前房价后", base, deletedAt)
            val word = if (kind == "same") "房价" else "放假"
            assertNull(kind, t.commit(if (kind == "code") "326" else "3264542", word,
                if (kind == "missing") null else base,
                if (kind == "position") "前后$word" else "前${if (kind == "partial") "房" else ""}${word}后", "new", replacedAt))
        }
    }
    @Test fun `含相同重复字符导致插入位置歧义时不追踪`() {
        val t = CorrectionLearningTracker()
        assertFalse(CorrectionLearningTracker.canTrack("的", "的", "的的"))
        t.commit("3", "的", "的", "的的", "reward", 0)
        t.delete("的的", "的", 1)
        assertNull(t.commit("3", "得", "的", "的得", "new", 2))
    }
}
