package com.yuyan.inputmethod.util

import org.junit.Assert.assertEquals
import org.junit.Test

class T9CompositionAlignmentTest {
    @Test fun `显式分词符保留`() {
        assertEquals("bu'", T9PinYinUtils.getT9Composition("AT'", "bu"))
    }

    @Test fun `首选变更后不沿用遍天下的一四一切分`() {
        assertEquals("bu'gao'x", T9PinYinUtils.getT9Composition("A'TGAM'W", "bu'gao'xing"))
    }
}
