package com.yuyan.imemodule.data.completion

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.*
import org.junit.Test

class InputLearningPolicyTest {
    @Test fun `密码和禁止个性化输入不学习或上报`() {
        assertFalse(canLearnInput(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, 0))
        assertFalse(canLearnInput(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, 0))
        assertFalse(canLearnInput(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, 0))
        assertFalse(canLearnInput(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD, 0))
        assertFalse(canLearnInput(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING))
        assertTrue(canLearnInput(InputType.TYPE_CLASS_TEXT, 0))
    }
}
