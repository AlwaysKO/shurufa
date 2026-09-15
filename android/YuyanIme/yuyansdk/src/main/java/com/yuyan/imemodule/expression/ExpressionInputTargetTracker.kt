package com.yuyan.imemodule.expression

import android.view.inputmethod.EditorInfo
import androidx.core.view.inputmethod.EditorInfoCompat

/** 区分同一编辑器的 restart 与真实的输入目标切换。 */
internal class ExpressionInputTargetTracker {
    private var previousEditor: EditorIdentity? = null
    private var previousConnection: Any? = null
    private var initialized = false

    fun shouldReset(editorInfo: EditorInfo, restarting: Boolean, connectionIdentity: Any?): Boolean {
        val editor = EditorIdentity.from(editorInfo)
        val changed = initialized && (
            editor != previousEditor ||
                (connectionIdentity != null && previousConnection != null && connectionIdentity !== previousConnection)
            )
        val reset = !initialized || !restarting || changed
        previousEditor = editor
        previousConnection = connectionIdentity
        initialized = true
        return reset
    }

    private data class EditorIdentity(
        val packageName: String?,
        val fieldId: Int,
        val inputType: Int,
        val imeOptions: Int,
        val privateImeOptions: String?,
        val contentMimeTypes: List<String>,
        val wechatChatEditor: Boolean,
    ) {
        companion object {
            fun from(editorInfo: EditorInfo) = EditorIdentity(
                packageName = editorInfo.packageName,
                fieldId = editorInfo.fieldId,
                inputType = editorInfo.inputType,
                imeOptions = editorInfo.imeOptions,
                privateImeOptions = editorInfo.privateImeOptions,
                contentMimeTypes = EditorInfoCompat.getContentMimeTypes(editorInfo).distinct().sorted(),
                wechatChatEditor = editorInfo.extras?.getBoolean(WECHAT_CHAT_EDITOR_KEY) == true,
            )
        }
    }
}
