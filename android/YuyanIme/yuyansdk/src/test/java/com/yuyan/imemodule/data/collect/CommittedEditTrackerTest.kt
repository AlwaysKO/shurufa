package com.yuyan.imemodule.data.collect

import android.text.SpannableStringBuilder
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.ExtractedText
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CommittedEditTrackerTest {
    @Test fun 连续输入中间删除替换保留完整链且清空后换组() {
        val tracker = CommittedEditTracker()
        val first = tracker.record("", "晚上八点见")!!
        val deletion = tracker.record("晚上八点见", "晚上点见")!!
        val replacement = tracker.record("晚上点见", "晚上九点见")!!
        assertEquals(first.sessionId, deletion.sessionId)
        assertEquals(first.sessionId, replacement.sessionId)
        assertEquals(3L, replacement.sequenceNo)
        assertEquals("八", deletion.removedText)
        assertEquals("九", replacement.insertedText)
        assertTrue(replacement.complete)
        val clear = tracker.record("晚上九点见", "")!!
        assertEquals(first.sessionId, clear.sessionId)
        assertNotEquals(first.sessionId, tracker.record("", "下一句")!!.sessionId)
    }

    @Test fun 失败不记成功而未知快照和不连续必须断组() {
        val tracker = CommittedEditTracker()
        assertNull(tracker.record("", "失败", successful = false))
        val first = tracker.record("", "你好")!!
        assertEquals(1L, first.sequenceNo)
        val unknown = tracker.record(null, null)!!
        assertFalse(unknown.complete)
        assertNotEquals(first.sessionId, unknown.sessionId)
        val next = tracker.record("其他聊天", "其他聊天内容")!!
        assertNotEquals(unknown.sessionId, next.sessionId)
        tracker.reset()
        assertNotEquals(next.sessionId, tracker.record("其他聊天内容", "其他聊天内容啊")!!.sessionId)
    }

    @Test fun 不变化不记删除尝试选区替换保留被替换内容() {
        val tracker = CommittedEditTracker()
        assertNull(tracker.record("原文", "原文"))
        val change = tracker.record("早上八点见", "晚上九点见")!!
        assertEquals("早上八", change.removedText)
        assertEquals("晚上九", change.insertedText)
    }

    @Test fun 快照排除未上屏拼音并拒绝局部与超长文本() {
        fun extracted(text: CharSequence) = ExtractedText().apply {
            this.text = text; startOffset = 0; partialStartOffset = -1; partialEndOffset = -1
        }
        val composing = SpannableStringBuilder("你好ni")
        BaseInputConnection.setComposingSpans(composing)
        assertEquals("", committedSnapshot(extracted(composing)))
        BaseInputConnection.removeComposingSpans(composing)
        assertEquals("你好ni", committedSnapshot(extracted(composing)))
        assertNull(committedSnapshot(extracted("局部").apply { startOffset = 2 }))
        assertNull(committedSnapshot(extracted("局部").apply { partialStartOffset = 0 }))
        assertNull(committedSnapshot(extracted("长".repeat(5001))))
        assertNull(committedSnapshot(null))
    }
}
