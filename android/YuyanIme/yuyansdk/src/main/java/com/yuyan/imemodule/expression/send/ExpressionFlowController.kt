package com.yuyan.imemodule.expression.send

import com.yuyan.imemodule.expression.model.EmojiCombination
import com.yuyan.imemodule.expression.model.ExpressionAsset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

enum class ExpressionSendStage { PREPARING, SENDING, SAVING }

class ExpressionFlowController(
    private val sendController: ExpressionSendController,
    private val prepareAsset: suspend (ExpressionAsset, String) -> PreparedExpression,
    private val prepareCombination: suspend (EmojiCombination) -> PreparedExpression,
    private val fallback: (suspend (PreparedExpression, ExpressionSendResult) -> ExpressionSendResult)? = null,
    private val timeoutMillis: Long = 30_000,
    private val onStage: (ExpressionSendStage) -> Unit = {},
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
        var stage = ExpressionSendStage.PREPARING
        fun report(next: ExpressionSendStage) {
            stage = next
            onStage(next)
        }
        return try {
            withTimeoutOrNull(timeoutMillis) {
                report(ExpressionSendStage.PREPARING)
                val expression = try {
                    prepare()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    sendController.cancel()
                    return@withTimeoutOrNull ExpressionSendResult.Failed(error.message?.takeIf(String::isNotBlank).orEmpty())
                }
                sendController.prepare(expression)
                report(ExpressionSendStage.SENDING)
                val directResult = sendController.confirm()
                val result = if (directResult == ExpressionSendResult.Sent ||
                    directResult == ExpressionSendResult.WechatSubmitted ||
                    directResult == ExpressionSendResult.AppSubmitted || fallback == null) {
                    directResult
                } else {
                    try {
                        report(ExpressionSendStage.SAVING)
                        fallback(expression, directResult)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        directResult
                    }
                }
                if (result != ExpressionSendResult.Sent && result != ExpressionSendResult.WechatSubmitted) sendController.cancel()
                result
            } ?: run {
                sendController.cancel()
                ExpressionSendResult.Failed(when (stage) {
                    ExpressionSendStage.PREPARING -> "准备图片超时，请重试"
                    ExpressionSendStage.SENDING -> "交付图片超时，请检查聊天记录后重试"
                    ExpressionSendStage.SAVING -> "保存图片超时，请检查相册后重试"
                })
            }
        } catch (cancelled: CancellationException) {
            sendController.cancel()
            throw cancelled
        } finally {
            directSendMutex.unlock()
        }
    }
}
