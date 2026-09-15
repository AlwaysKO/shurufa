package com.yuyan.imemodule.expression

import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [28])
class ExpressionInputTargetTrackerTest {
    @Test
    fun `同输入框图片能力或聊天标记变化也要清理推荐`() {
        val tracker = ExpressionInputTargetTracker()
        val editor = EditorInfo().apply {
            extras = android.os.Bundle().apply { putBoolean("IS_CHAT_EDITOR", true) }
        }
        val connection = Any()
        androidx.core.view.inputmethod.EditorInfoCompat.setContentMimeTypes(editor, arrayOf("image/*"))
        tracker.shouldReset(editor, false, connection)
        androidx.core.view.inputmethod.EditorInfoCompat.setContentMimeTypes(editor, emptyArray())
        assertTrue(tracker.shouldReset(editor, true, connection))
        editor.extras!!.putBoolean("IS_CHAT_EDITOR", false)
        assertTrue(tracker.shouldReset(editor, true, connection))
    }

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
