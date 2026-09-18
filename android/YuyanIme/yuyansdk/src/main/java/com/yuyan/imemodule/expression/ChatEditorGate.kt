package com.yuyan.imemodule.expression

import android.text.InputType
import android.content.ClipDescription
import android.view.inputmethod.EditorInfo
import androidx.core.view.inputmethod.EditorInfoCompat

internal const val WECHAT_CHAT_EDITOR_KEY = "IS_CHAT_EDITOR"

class ChatEditorGate(
    private val chatPackages: Set<String> = DEFAULT_CHAT_PACKAGES,
) {
    fun allows(packageName: String?, editorInfo: EditorInfo?): Boolean {
        if (packageName !in chatPackages || !isChatTextEditor(editorInfo)) return false
        editorInfo ?: return false
        // 微信公众号评论等也可能是多行/SEND；沿用已有 Emoji 适配中的真实聊天标记。
        if (packageName == "com.tencent.mm" &&
            editorInfo.extras?.getBoolean(WECHAT_CHAT_EDITOR_KEY) != true) return false
        return EditorInfoCompat.getContentMimeTypes(editorInfo).any { accepted ->
            IMAGE_MIME_TYPES.any { ClipDescription.compareMimeTypes(it, accepted) }
        }
    }

    fun requiresManualSearch(packageName: String?): Boolean = packageName in MANUAL_SEARCH_PACKAGES

    /** 用户主动搜索与宿主接收图片能力分开判断；发送时仍由发送端检查。 */
    fun allowsManualSearch(packageName: String?, editorInfo: EditorInfo?): Boolean =
        requiresManualSearch(packageName) && isChatTextEditor(editorInfo)

    private fun isChatTextEditor(editorInfo: EditorInfo?): Boolean {
        if (editorInfo == null) return false
        if (editorInfo.inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) {
            return false
        }
        val action = editorInfo.imeOptions and EditorInfo.IME_MASK_ACTION
        if (action == EditorInfo.IME_ACTION_SEARCH) return false
        val variation = editorInfo.inputType and InputType.TYPE_MASK_VARIATION
        if (variation in BLOCKED_VARIATIONS) return false
        val chatLike = editorInfo.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0 ||
            action == EditorInfo.IME_ACTION_SEND
        if (!chatLike) return false
        return true
    }

    private companion object {
        val MANUAL_SEARCH_PACKAGES = setOf("com.tencent.mobileqq", "com.ss.android.ugc.aweme")
        val IMAGE_MIME_TYPES = listOf("image/gif", "image/png", "image/webp", "image/jpeg")
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
