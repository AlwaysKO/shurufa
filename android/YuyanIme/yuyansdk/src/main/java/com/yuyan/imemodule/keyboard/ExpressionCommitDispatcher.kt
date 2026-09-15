package com.yuyan.imemodule.keyboard

/** 保留旧 AAR 调用入口；新提交路径由 HostTextCommitDispatcher 负责报告真实宿主结果。 */
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
