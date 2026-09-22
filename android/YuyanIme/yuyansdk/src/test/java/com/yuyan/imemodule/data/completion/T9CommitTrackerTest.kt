package com.yuyan.imemodule.data.completion

import org.junit.Assert.*
import org.junit.Test

class T9CommitTrackerTest {
    @Test fun `三键末音节补全提交保留我们读音和实际输入码`() {
        val tracker = T9CommitTracker()
        tracker.segment("966", "我们", "wo men", "我们")
        val selected = tracker.consumeSelection("我们", true)
        assertEquals("966", selected?.code)
        assertEquals("wo men", selected?.pinyin)
        assertNull(tracker.consumeSelection("我们", true))
    }

    @Test fun `锁音还原的整码不能覆盖之前明确选中的段`() {
        val tracker = T9CommitTracker()
        tracker.segment("8245464", "泰", "tai", null)
        tracker.segment("8245464", "鲮", "ling", "泰鲮")
        val result = tracker.consumeSelection("泰鲮", true)
        assertEquals("8245464", result?.code)
        assertEquals("tai ling", result?.pinyin)
    }

    @Test fun `错误读音和只选后半词不得误学成整码`() {
        val tracker = T9CommitTracker()
        tracker.segment("8245464", "泰", "ta", null)
        tracker.segment("", "鲮", "ling", "泰鲮")
        assertNull(tracker.consumeSelection("泰鲮", true))
        tracker.segment("8245464", "鲮", "ling", "泰鲮")
        assertNull(tracker.consumeSelection("泰鲮", true))
    }

    @Test fun `分段选词保存最初编码与整句读音`() {
        val tracker = T9CommitTracker()
        tracker.segment("94363362", "真的", "zhen'de", null)
        tracker.segment("", "吗", "ma", "真的吗")
        val result = tracker.consumeSelection("真的吗", true)
        assertEquals("94363362", result?.code)
        assertEquals("zhen de ma", result?.pinyin)
        assertNull(tracker.consumeSelection("真的吗", true))
    }
    @Test fun `取消或宿主失败不保留分段学习`() {
        val tracker = T9CommitTracker()
        tracker.segment("9267426548", "玩", "wan", null)
        tracker.clear()
        tracker.segment("", "漂流", "piao'liu", "玩漂流")
        assertNull(tracker.consumeSelection("玩漂流", true))
        tracker.segment("9267426548", "玩漂流", "wan piao liu", "玩漂流")
        assertNull(tracker.consumeSelection("玩漂流", false))
        assertNull(tracker.consumeSelection("玩漂流", true))
    }

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
