package com.yuyan.imemodule.expression

import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpressionInputTargetTrackerTest {
    @Test
    fun `同一编辑器restarting保留会话而非重启清理`() {
        val tracker = ExpressionInputTargetTracker()
        val editor = EditorInfo().apply {
            packageName = "com.example.chat"
            fieldId = 7
            inputType = 1
        }
        val connection = Any()

        assertTrue(tracker.shouldReset(editor, restarting = false, connectionIdentity = connection))
        assertFalse(tracker.shouldReset(editor, restarting = true, connectionIdentity = connection))
        assertTrue(tracker.shouldReset(editor, restarting = false, connectionIdentity = connection))
    }

    @Test
    fun `restarting但输入连接或编辑器变化仍清理`() {
        val tracker = ExpressionInputTargetTracker()
        val first = EditorInfo().apply { packageName = "com.example.chat"; fieldId = 1 }
        val second = EditorInfo().apply { packageName = "com.example.chat"; fieldId = 2 }
        val firstConnection = Any()

        assertTrue(tracker.shouldReset(first, restarting = false, connectionIdentity = firstConnection))
        assertTrue(tracker.shouldReset(first, restarting = true, connectionIdentity = Any()))
        assertTrue(tracker.shouldReset(second, restarting = true, connectionIdentity = firstConnection))
    }
}
