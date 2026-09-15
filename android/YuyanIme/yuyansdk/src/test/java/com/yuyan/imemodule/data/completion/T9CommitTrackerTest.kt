package com.yuyan.imemodule.data.completion

import org.junit.Assert.*
import org.junit.Test

class T9CommitTrackerTest {
    @Test fun `只有匹配的成功上屏才返回原始编码且只消费一次`() {
        val tracker = T9CommitTracker()
        tracker.selected("46898262", "候选词")
        assertNull(tracker.consume("其他", true))
        tracker.selected("46898262", "候选词")
        assertNull(tracker.consume("候选词", false))
        tracker.selected("46898262", "候选词")
        assertEquals("46898262", tracker.consume("候选词", true))
        assertNull(tracker.consume("候选词", true))
    }
    @Test fun `清理组合时不会把旧选择关联到下一次提交`() {
        val tracker = T9CommitTracker()
        tracker.selected("46898262", "候选词")
        tracker.clear()
        assertNull(tracker.consume("候选词", true))
    }
}
