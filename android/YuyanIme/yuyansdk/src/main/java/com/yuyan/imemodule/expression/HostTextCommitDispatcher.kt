package com.yuyan.imemodule.expression

/** 把宿主提交成功作为记录斗图文本的唯一门槛。 */
internal object HostTextCommitDispatcher {
    fun dispatch(
        text: String,
        kind: ExpressionCommitKind,
        commitToHost: () -> Boolean,
        notifyCommitted: (String, ExpressionCommitKind) -> Unit,
    ): Boolean {
        val committed = commitToHost()
        if (committed) notifyCommitted(text, kind)
        return committed
    }
}
