package com.yuyan.imemodule.expression

import android.text.InputType
import android.view.inputmethod.EditorInfo

class ChatEditorGate(
    private val chatPackages: Set<String> = DEFAULT_CHAT_PACKAGES,
) {
    fun allows(packageName: String?, editorInfo: EditorInfo?): Boolean {
        if (packageName !in chatPackages || editorInfo == null) return false
        if (editorInfo.inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) {
            return false
        }
        val action = editorInfo.imeOptions and EditorInfo.IME_MASK_ACTION
        if (action == EditorInfo.IME_ACTION_SEARCH) return false
        val variation = editorInfo.inputType and InputType.TYPE_MASK_VARIATION
        if (variation in BLOCKED_VARIATIONS) return false
        return editorInfo.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0 ||
            action == EditorInfo.IME_ACTION_SEND
    }

    private companion object {
        val DEFAULT_CHAT_PACKAGES = setOf(
            "com.tencent.mm",
            "com.tencent.mobileqq",
            "com.hihonor.mms",
            "com.ss.android.lark",
            "org.telegram.messenger",
            "com.whatsapp",
            "com.discord",
        )
        val BLOCKED_VARIATIONS = setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_URI,
        )
    }
}
