package com.yuyan.imemodule.expression.send

import com.yuyan.imemodule.expression.model.EmojiCombination
import com.yuyan.imemodule.expression.model.ExpressionAsset
import kotlinx.coroutines.sync.Mutex

class ExpressionFlowController(
    private val sendController: ExpressionSendController,
    private val prepareAsset: suspend (ExpressionAsset, String) -> PreparedExpression,
    private val prepareCombination: suspend (EmojiCombination) -> PreparedExpression,
) {
    private val directSendMutex = Mutex()

    suspend fun prepare(asset: ExpressionAsset, query: String): PreparedExpression =
        prepareAsset(asset, query).also(sendController::prepare)

    suspend fun prepare(combination: EmojiCombination): PreparedExpression =
        prepareCombination(combination).also(sendController::prepare)

    suspend fun prepareAndSend(asset: ExpressionAsset, query: String): ExpressionSendResult =
        prepareAndSend { prepareAsset(asset, query) }

    suspend fun prepareAndSend(combination: EmojiCombination): ExpressionSendResult =
        prepareAndSend { prepareCombination(combination) }

    fun cancel() = sendController.cancel()

    suspend fun confirm(): ExpressionSendResult = sendController.confirm()

    private suspend fun prepareAndSend(
        prepare: suspend () -> PreparedExpression,
    ): ExpressionSendResult {
        if (!directSendMutex.tryLock()) return ExpressionSendResult.AlreadySending
        return try {
            val expression = try {
                prepare()
            } catch (error: Exception) {
                sendController.cancel()
                return ExpressionSendResult.Failed(error.message?.takeIf(String::isNotBlank) ?: "图片准备失败")
            }
            sendController.prepare(expression)
            sendController.confirm().also { result ->
                if (result != ExpressionSendResult.Sent) sendController.cancel()
            }
        } finally {
            directSendMutex.unlock()
        }
    }
}
