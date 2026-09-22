package com.yuyan.inputmethod.data

import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class T9RawInputTest {
    @Test fun `锁音和选字只为最终学习保留等价整码不开放本地候选`() {
        val stack = KeyRecordStack()
        @Suppress("UNCHECKED_CAST")
        val records = KeyRecordStack::class.java.getDeclaredField("keyRecords").run {
            isAccessible = true; get(stack) as MutableList<InputKey>
        }
        records.add(InputKey.PinyinKey("tai"))
        "JGMG".forEach { records.add(InputKey.T9Key(it)) }
        stack.pushCandidateSelectAction()
        assertEquals("", stack.unlockedT9Digits())
        assertEquals("8245464", stack.compositionT9Digits())
        records.add(InputKey.Apostrophe())
        assertEquals("", stack.compositionT9Digits())
        records.removeAt(records.lastIndex)
        records.add(InputKey.DefaultAction)
        assertEquals("", stack.compositionT9Digits())
    }

    @Test fun `未分段九宫格记录还原原始数字且删除同步`() {
        val stack = KeyRecordStack()
        for (key in listOf(KeyEvent.KEYCODE_G, KeyEvent.KEYCODE_M, KeyEvent.KEYCODE_T, KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_T, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_M, KeyEvent.KEYCODE_A)) {
            stack.pushKey(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, key, 0, KeyEvent.META_SHIFT_ON))
        }
        assertEquals("46898262", stack.unlockedT9Digits())
        stack.pop()
        assertEquals("4689826", stack.unlockedT9Digits())
        stack.pushCandidateSelectAction()
        assertEquals("", stack.unlockedT9Digits())
    }

    @Test fun `标准全键保存原始混拼且锁定后不再学习整码`() {
        val stack = KeyRecordStack()
        listOf(KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_U, KeyEvent.KEYCODE_Q).forEach {
            stack.pushKey(KeyEvent(KeyEvent.ACTION_DOWN, it))
        }
        assertEquals("xuq", stack.unlockedPinyin())
        assertEquals("", stack.unlockedT9Digits())
        stack.pushCandidateSelectAction()
        assertEquals("", stack.unlockedPinyin())
    }
}
