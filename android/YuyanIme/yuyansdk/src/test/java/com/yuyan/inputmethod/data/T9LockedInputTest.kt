package com.yuyan.inputmethod.data

import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class T9LockedInputTest {
    private fun stack(): KeyRecordStack = KeyRecordStack { "t9_pinyin" }.apply {
        for (key in listOf(KeyEvent.KEYCODE_M, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_M, KeyEvent.KEYCODE_P)) {
            pushKey(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, key, 0, KeyEvent.META_SHIFT_ON))
        }
    }
    @Test fun `连续锁定nan和s保留真实音节及等价提交编码`() {
        val stack = stack()
        assertNull(stack.lockedT9Input())
        assertNotNull(stack.pushPinyinSelectAction("nan"))
        assertEquals("nan'7", stack.lockedT9Input())
        assertNotNull(stack.pushPinyinSelectAction("s"))
        assertEquals("nan's'", stack.lockedT9Input())
        assertEquals("6267", stack.compositionT9Digits())
        assertEquals("", stack.unlockedT9Digits())
    }
    @Test fun `替换失败不得留下虚假的锁音记录`() {
        val stack = stack()
        assertNull(stack.pushPinyinSelectAction("nan") { false })
        assertEquals("6267", stack.unlockedT9Digits())
        assertNull(stack.lockedT9Input())
        assertNotNull(stack.pushPinyinSelectAction("nan") { true })
        assertNull(stack.pushPinyinSelectAction("s") { false })
        assertEquals("nan'7", stack.lockedT9Input())
    }
    @Test fun `没有对应按键不能误锁已有拼音`() {
        val stack = stack()
        assertNull(stack.pushPinyinSelectAction("bei"))
        assertEquals("6267", stack.unlockedT9Digits())
    }
    @Test fun `已选字或显式分词后不把剩余候选当整个组合提交`() {
        val stack = stack()
        stack.pushPinyinSelectAction("nan")
        stack.pushCandidateSelectAction()
        assertNull(stack.lockedT9Input())
        val split = stack()
        split.pushPinyinSelectAction("nan")
        split.pushKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_APOSTROPHE))
        assertNull(split.lockedT9Input())
    }
    @Test fun `回退锁音能还原数字和原有候选路径`() {
        val stack = stack()
        stack.pushPinyinSelectAction("nan")
        stack.pushPinyinSelectAction("s")
        assertEquals(InputKey.SelectPinyinAction, stack.pop())
        assertNotNull(stack.restorePinyinToT9Key())
        assertEquals("nan'7", stack.lockedT9Input())
        assertTrue(stack.pop() is InputKey.T9Key)
        val nan = stack.pop() as InputKey.PinyinKey
        assertNotNull(stack.restorePinyinToT9Key(nan))
        assertNull(stack.lockedT9Input())
        assertEquals("626", stack.unlockedT9Digits())
    }
}
