package com.yuyan.inputmethod.data

import org.junit.Assert.*
import org.junit.Test

class T9DeferredNativeInputTest {
    @Test fun `延后输入只保存原生基准并保留本地尾段删除权`() {
        val state = T9DeferredNativeInput()
        assertFalse(state.isActive)
        assertFalse(state.shouldHandleDelete(12))
        state.start(11)
        assertTrue(state.isActive)
        assertEquals(11, state.nativeLength)
        assertTrue(state.shouldHandleDelete(14))
        state.afterDelete(13)
        assertTrue(state.isActive)
        assertEquals(11, state.nativeLength)
        assertTrue(state.shouldHandleDelete(13))
    }

    @Test fun `删除到原生基准只结束延后状态不会再删除原生键`() {
        val state = T9DeferredNativeInput()
        state.start(11)
        assertFalse(state.shouldHandleDelete(11))
        assertTrue(state.shouldHandleDelete(12))
        state.afterDelete(11)
        assertFalse(state.isActive)
        assertFalse(state.shouldHandleDelete(11))
        assertEquals(0, state.nativeLength)
    }

    @Test fun `清空和后续新组合不会继承旧基准`() {
        val state = T9DeferredNativeInput()
        state.start(12)
        state.clear()
        assertFalse(state.isActive)
        assertEquals(0, state.nativeLength)
        state.start(16)
        assertEquals(16, state.nativeLength)
        state.afterDelete(0)
        assertFalse(state.isActive)
        state.clear()
        assertEquals(0, state.nativeLength)
    }
}
