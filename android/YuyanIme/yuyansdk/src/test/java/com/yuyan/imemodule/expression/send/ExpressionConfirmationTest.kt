package com.yuyan.imemodule.expression.send

import org.junit.Assert.*
import org.junit.Test

class ExpressionConfirmationTest {
    private fun tracker(): Any = Class.forName("com.yuyan.imemodule.expression.send.ExpressionConfirmation").getDeclaredConstructor().newInstance()
    private fun call(target: Any, name: String, vararg args: Any): Any? = target.javaClass.methods.single { it.name == name }.invoke(target, *args)
    @Test fun `交接打开弹框不等于确认且只有本次发送按钮消费一次`() {
        val t = tracker()
        call(t, "begin", 10, 100L)
        assertEquals(false, call(t, "isConfirmed", 101L))
        call(t, "dialog", 20, 102L)
        assertEquals(false, call(t, "isConfirmed", 103L))
        call(t, "click", 21, true, 104L)
        assertEquals(false, call(t, "isConfirmed", 105L))
        call(t, "begin", 10, 105L)
        call(t, "dialog", 20, 105L)
        call(t, "click", 20, true, 106L)
        assertEquals(true, call(t, "isConfirmed", 107L))
        call(t, "cancel")
        assertEquals(false, call(t, "isConfirmed", 108L))
    }
    @Test fun `取消返回超时和旧事件不能确认`() {
        val t = tracker()
        call(t, "begin", 10, 100L)
        call(t, "dialog", 20, 101L)
        call(t, "click", 20, false, 102L)
        call(t, "click", 20, true, 103L)
        assertEquals(false, call(t, "isConfirmed", 104L))
        call(t, "begin", 10, 200L)
        call(t, "dialog", 20, 190L)
        call(t, "click", 20, true, 191L)
        assertEquals(false, call(t, "isConfirmed", 201L))
        call(t, "dialog", 20, 202L)
        assertEquals(false, call(t, "isConfirmed", 120_201L))
    }
}
