package com.yuyan.imemodule.data.completion

import org.junit.Assert.*
import org.junit.Test

class CompletionSnapshotPauseTest {
    private val old = CompletionCandidate(1, "你", "你好", 1, 1)
    private val updated = old.copy(useCount = 9, version = 2)
    private val added = CompletionCandidate(2, "再", "再见", 1, 2)

    @Test fun `中途暂停不修改已发布的旧补全快照`() {
        val current = mapOf("你" to listOf(old))
        var checks = 0
        val next = mergeCompletionCandidates(current, listOf(updated, added)) { ++checks < 3 }
        assertNull(next)
        assertEquals(mapOf("你" to listOf(old)), current)
    }

    @Test fun `完成全部更新后返回独立可发布快照`() {
        val current = mapOf("你" to listOf(old))
        val next = mergeCompletionCandidates(current, listOf(updated, added)) { true }!!
        assertEquals(listOf(updated), next["你"])
        assertEquals(listOf(added), next["再"])
        assertEquals(listOf(old), current["你"])
        next["你"]!!.clear()
        assertEquals(listOf(old), current["你"])
    }
}
