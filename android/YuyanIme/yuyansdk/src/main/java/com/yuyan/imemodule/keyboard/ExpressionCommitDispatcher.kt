package com.yuyan.imemodule.keyboard

internal object ExpressionCommitDispatcher {
    fun dispatch(
        text: String?,
        commitText: (String?) -> Unit,
        notifyExpression: (String) -> Unit,
    ) {
        commitText(text)
        text?.takeIf(String::isNotEmpty)?.also(notifyExpression)
    }
}
