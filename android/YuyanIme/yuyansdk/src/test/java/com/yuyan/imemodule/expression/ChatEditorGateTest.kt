package com.yuyan.imemodule.expression

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatEditorGateTest {
    private val gate = ChatEditorGate()

    @Test
    fun `白名单聊天应用的多行或发送输入框允许推荐`() {
        val packages = listOf(
            "com.tencent.mm",
            "com.tencent.mobileqq",
            "com.hihonor.mms",
            "com.ss.android.lark",
            "org.telegram.messenger",
            "com.whatsapp",
            "com.discord",
        )

        packages.forEach { packageName ->
            assertTrue(packageName, gate.allows(packageName, multilineEditor()))
        }
        assertTrue(
            gate.allows(
                "com.whatsapp",
                editor(imeOptions = EditorInfo.IME_ACTION_SEND),
            ),
        )
    }

    @Test
    fun `非聊天应用即使多行也不允许推荐`() {
        assertFalse(gate.allows("com.android.settings", multilineEditor()))
        assertFalse(gate.allows("com.google.android.keep", multilineEditor()))
    }

    @Test
    fun `聊天应用排除搜索密码邮箱网址和普通单行框`() {
        val packageName = "com.tencent.mm"
        assertFalse(gate.allows(packageName, editor(imeOptions = EditorInfo.IME_ACTION_SEARCH)))
        assertFalse(gate.allows(packageName, editor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)))
        assertFalse(gate.allows(packageName, editor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)))
        assertFalse(gate.allows(packageName, editor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)))
        assertFalse(gate.allows(packageName, editor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)))
        assertFalse(gate.allows(packageName, editor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)))
        assertFalse(gate.allows(packageName, editor()))
    }

    private fun multilineEditor() = editor(
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
    )

    private fun editor(
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        imeOptions: Int = EditorInfo.IME_ACTION_NONE,
    ) = EditorInfo().apply {
        this.inputType = inputType
        this.imeOptions = imeOptions
    }
}
