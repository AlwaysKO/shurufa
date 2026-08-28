package com.yuyan.imemodule.keyboard

internal object ExpressionCommitDispatcher {
    fun dispatch(
        text: String?,
        commitText: (String?) -> Boolean,
        notifyExpression: (String) -> Unit,
    ) {
        val committedToHost = commitText(text)
        if (committedToHost) {
            text?.takeIf(String::isNotEmpty)?.also(notifyExpression)
        }
    }
}
