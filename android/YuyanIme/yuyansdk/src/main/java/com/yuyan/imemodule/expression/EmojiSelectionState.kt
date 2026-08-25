package com.yuyan.imemodule.expression

enum class EmojiSelectionStep {
    FIRST,
    SECOND,
    PREVIEW,
}

class EmojiSelectionState {
    var firstId: String? = null
        private set
    var secondId: String? = null
        private set
    var step: EmojiSelectionStep = EmojiSelectionStep.FIRST
        private set

    val combinationKey: String?
        get() = firstId?.let { first -> secondId?.let { second -> "${first}__${second}" } }

    fun select(id: String): String? {
        require(id.isNotBlank()) { "emoji id must not be blank" }
        return when (step) {
            EmojiSelectionStep.FIRST -> {
                firstId = id
                secondId = null
                step = EmojiSelectionStep.SECOND
                null
            }
            EmojiSelectionStep.SECOND -> {
                secondId = id
                step = EmojiSelectionStep.PREVIEW
                combinationKey
            }
            EmojiSelectionStep.PREVIEW -> combinationKey
        }
    }

    fun backToFirst() {
        secondId = null
        step = EmojiSelectionStep.FIRST
    }
}
