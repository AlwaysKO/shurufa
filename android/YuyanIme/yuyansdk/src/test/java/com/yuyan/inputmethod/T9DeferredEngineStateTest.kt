package com.yuyan.inputmethod

import com.yuyan.inputmethod.data.InputKey
import com.yuyan.inputmethod.data.KeyRecordStack
import com.yuyan.inputmethod.data.T9DeferredNativeInput
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test

class T9DeferredEngineStateTest {
    private val stack = RimeEngine::class.java.getDeclaredField("keyRecordStack").run { isAccessible = true; get(RimeEngine) as KeyRecordStack }
    private val deferred = RimeEngine::class.java.getDeclaredField("deferredNativeInput").run { isAccessible = true; get(RimeEngine) as T9DeferredNativeInput }
    @Before fun setup() {
        stack.clear(); deferred.clear()
        @Suppress("UNCHECKED_CAST")
        val records = KeyRecordStack::class.java.getDeclaredField("keyRecords").run { isAccessible = true; get(stack) as MutableList<InputKey> }
        "643645269497".forEach { records.add(InputKey.T9Key("ADGJMPTW"[it - '2'])) }
        deferred.start(8)
    }
    @After fun cleanup() { deferred.clear(); stack.clear(); RimeEngine.clearCachedCompositionForSchemaSwitch() }
    @Test fun `暂停期间退格不调用原生且退到原生边界才恢复`() {
        RimeEngine.processDelAction()
        assertEquals("64364526949", stack.unlockedT9Digits())
        assertTrue(deferred.isActive)
        repeat(3) { RimeEngine.processDelAction() }
        assertEquals("64364526", stack.unlockedT9Digits())
        assertFalse(deferred.isActive)
    }
    @Test fun `恢复原生一次替换完整输入且失败不丢尾部`() {
        assertFalse(RimeEngine.restoreDeferredNativeInput { _, _, _ -> false })
        assertTrue(deferred.isActive)
        assertEquals("643645269497", stack.unlockedT9Digits())
        var calls = 0
        assertTrue(RimeEngine.restoreDeferredNativeInput { pos, length, keys ->
            calls++
            assertEquals(0, pos); assertEquals(8, length)
            assertEquals("643645269497", com.yuyan.imemodule.data.completion.T9Lexicon.digits(keys))
            true
        })
        assertEquals(1, calls)
        assertFalse(deferred.isActive)
        assertEquals("643645269497", stack.unlockedT9Digits())
    }
    @Test fun `切换schema清除暂停标记以免下一次输入继承旧边界`() {
        RimeEngine.clearCachedCompositionForSchemaSwitch()
        assertFalse(deferred.isActive)
    }
}
