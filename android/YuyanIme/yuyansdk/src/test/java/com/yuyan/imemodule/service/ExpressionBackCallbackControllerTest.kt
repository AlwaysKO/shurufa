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

    @Test
    fun `返回回调内的注销延迟到事件消费完成后执行`() {
        var unregistrations = 0
        var fallbacks = 0
        var posted: (() -> Unit)? = null
        lateinit var controller: ExpressionBackCallbackController
        controller = ExpressionBackCallbackController(
            register = {},
            unregister = { unregistrations += 1 },
        )
        controller.setEnabled(true)

        controller.onBackInvoked(
            handleBack = {
                controller.setEnabled(false)
                true
            },
            fallback = { fallbacks += 1 },
            post = { posted = it },
        )

        assertEquals(0, unregistrations)
        assertEquals(0, fallbacks)
        posted!!.invoke()
        assertEquals(1, unregistrations)
    }
}
