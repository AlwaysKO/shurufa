package com.yuyan.imemodule.data.capture.adapter

import com.yuyan.imemodule.data.capture.model.CapturedConversation
import com.yuyan.imemodule.data.capture.model.CapturedMessage
import com.yuyan.imemodule.data.capture.ui.UiNodeSnapshot

interface ChatAppAdapter {
    val packageName: String
    fun parse(root: UiNodeSnapshot): ParseResult
}

data class ParsedViewport(
    val conversation: CapturedConversation,
    val messages: List<CapturedMessage>,
)

sealed interface ParseResult {
    data class Success(val viewport: ParsedViewport) : ParseResult
    data class Skip(val reason: SkipReason) : ParseResult
}

enum class SkipReason {
    AMBIGUOUS_CONVERSATION,
    LOW_IDENTITY_CONFIDENCE,
    UNSUPPORTED_PAGE,
    UNSUPPORTED_VERSION,
}
