package com.yuyan.imemodule.expression.send

import com.yuyan.imemodule.expression.model.EmojiCombination
import com.yuyan.imemodule.expression.model.ExpressionAsset

class ExpressionFlowController(
    private val sendController: ExpressionSendController,
    private val prepareAsset: suspend (ExpressionAsset, String) -> PreparedExpression,
    private val prepareCombination: suspend (EmojiCombination) -> PreparedExpression,
) {
    suspend fun prepare(asset: ExpressionAsset, query: String): PreparedExpression =
        prepareAsset(asset, query).also(sendController::prepare)

    suspend fun prepare(combination: EmojiCombination): PreparedExpression =
        prepareCombination(combination).also(sendController::prepare)

    fun cancel() = sendController.cancel()

    suspend fun confirm(): ExpressionSendResult = sendController.confirm()
}
