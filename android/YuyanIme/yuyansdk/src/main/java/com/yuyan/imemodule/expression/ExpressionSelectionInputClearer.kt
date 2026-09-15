package com.yuyan.imemodule.expression

/** 选中斗图后立即清空当前编辑内容，不等待图片准备或发送结果。 */
internal object ExpressionSelectionInputClearer {
    private const val MAX_CURSOR_TEXT_LENGTH = 1_000

    fun clear(
        clearComposition: () -> Unit,
        selectAllText: () -> Boolean,
        replaceSelectedText: () -> Boolean,
        textBeforeCursor: (Int) -> CharSequence?,
        textAfterCursor: (Int) -> CharSequence?,
        deleteSurroundingText: (beforeLength: Int, afterLength: Int) -> Boolean,
    ): Boolean {
        clearComposition()
        val selectedAll = selectAllText()
        if (selectedAll && !replaceSelectedText()) return false
        repeat(MAX_DELETE_ROUNDS) {
            val before = textBeforeCursor(MAX_CURSOR_TEXT_LENGTH)
            val after = textAfterCursor(MAX_CURSOR_TEXT_LENGTH)
            if (before == null || after == null) return selectedAll
            if (before.isEmpty() && after.isEmpty()) return true
            if (!deleteSurroundingText(before.length, after.length)) return false
        }
        return false
    }

    private const val MAX_DELETE_ROUNDS = 64
}
