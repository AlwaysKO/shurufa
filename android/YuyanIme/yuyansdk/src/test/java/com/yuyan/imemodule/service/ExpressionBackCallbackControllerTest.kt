package com.yuyan.imemodule.service

import org.junit.Assert.assertEquals
import org.junit.Test

class ExpressionBackCallbackControllerTest {
    @Test
    fun `仅在展开状态变化时注册或注销返回回调`() {
        var registrations = 0
        var unregistrations = 0
        val controller = ExpressionBackCallbackController(
            register = { registrations += 1 },
            unregister = { unregistrations += 1 },
        )

        controller.setEnabled(true)
        controller.setEnabled(true)
        controller.setEnabled(false)
        controller.setEnabled(false)

        assertEquals(1, registrations)
        assertEquals(1, unregistrations)
    }

    @Test
    fun `清理时注销已注册的返回回调`() {
        var unregistrations = 0
        val controller = ExpressionBackCallbackController(
            register = {},
            unregister = { unregistrations += 1 },
        )
        controller.setEnabled(true)

        controller.clear()

        assertEquals(1, unregistrations)
    }
}
