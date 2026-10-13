package com.yuyan.imemodule.data.completion

import org.junit.Assert.*
import org.junit.Test

class T9InputConstraintTest {
    @Test fun `完整锁音不能扩写为另一个音节`() {
        val input = requireNotNull(T9InputConstraint.parse("nan'"))
        assertTrue(input.matches("nan"))
        assertFalse(input.matches("nang"))
        assertFalse(input.matches("nan shan"))
    }
    @Test fun `末尾声母支持补全且前面音节必须完整一致`() {
        val input = requireNotNull(T9InputConstraint.parse("nan's'"))
        assertTrue(input.matches("nan shan"))
        assertTrue(input.matches("nan song"))
        assertFalse(input.matches("na ni"))
        assertFalse(input.matches("nan ren"))
        assertTrue(input.matches("nan", allowPartial = true))
        assertFalse(input.matches("nan"))
    }
    @Test fun `数字段与锁音段混合时仍保留音节边界`() {
        val input = requireNotNull(T9InputConstraint.parse("626's"))
        assertEquals("6267", input.digits)
        assertTrue(input.matches("nan shan"))
        assertTrue(input.matches("man shan"))
        assertFalse(input.matches("na ni"))
        assertFalse(input.matches("nan ren"))
        assertTrue(requireNotNull(T9InputConstraint.parse("nan'74")).matches("nan shan"))
    }
    @Test fun `无锁音和非法分段不构造约束`() {
        for (input in listOf("6267", "nans", "nan''s", "nan'1", "nan'7s")) {
            assertNull(input, T9InputConstraint.parse(input))
        }
    }
    @Test fun `锁定声母后继续按键仍能完成同一个音节`() {
        assertTrue(requireNotNull(T9InputConstraint.parse("nan's'426")).matches("nan shan"))
        assertTrue(requireNotNull(T9InputConstraint.parse("nan's'42")).matches("nan shan"))
        assertFalse(requireNotNull(T9InputConstraint.parse("nan's'426")).matches("nan ren"))
    }
}
