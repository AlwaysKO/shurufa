package com.yuyan.imemodule.expression.send

import com.yuyan.imemodule.expression.model.EmojiCombination
import com.yuyan.imemodule.expression.model.ExpressionAsset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex

class ExpressionFlowController(
    private val sendController: ExpressionSendController,
    private val prepareAsset: suspend (ExpressionAsset, String) -> PreparedExpression,
    private val prepareCombination: suspend (EmojiCombination) -> PreparedExpression,
    private val fallback: suspend (PreparedExpression, ExpressionSendResult) -> ExpressionSendResult =
        { _, failure -> failure },
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
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                sendController.cancel()
                return ExpressionSendResult.Failed(error.message?.takeIf(String::isNotBlank).orEmpty())
            }
            sendController.prepare(expression)
            val directResult = sendController.confirm()
            val result = if (directResult == ExpressionSendResult.Sent) {
                directResult
            } else {
                try {
                    fallback(expression, directResult)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    directResult
                }
            }
            if (result != ExpressionSendResult.Sent) sendController.cancel()
            result
        } catch (cancelled: CancellationException) {
            sendController.cancel()
            throw cancelled
        } finally {
            directSendMutex.unlock()
        }
    }
}
