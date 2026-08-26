package com.yuyan.imemodule.expression.send

import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class PreparedExpression(
    val file: File,
    val mimeType: String,
    val displayName: String = file.name,
)

fun interface ExpressionSender {
    suspend fun send(expression: PreparedExpression): ExpressionSendResult
}

sealed interface ExpressionSendResult {
    data object Sent : ExpressionSendResult
    data object UnsupportedTarget : ExpressionSendResult
    data class Failed(val reason: String) : ExpressionSendResult
    data object NotPrepared : ExpressionSendResult
    data object AlreadySending : ExpressionSendResult
}

sealed interface ExpressionSendState {
    data object Idle : ExpressionSendState
    data class Ready(val expression: PreparedExpression) : ExpressionSendState
    data class Sending(val expression: PreparedExpression) : ExpressionSendState
    data class Failed(
        val expression: PreparedExpression,
        val result: ExpressionSendResult,
    ) : ExpressionSendState
}

class ExpressionSendController(
    private val sender: ExpressionSender,
) {
    private val mutex = Mutex()

    var state: ExpressionSendState = ExpressionSendState.Idle
        private set
    var prepared: PreparedExpression? = null
        private set

    val shouldClose: Boolean
        get() = state == ExpressionSendState.Idle && prepared == null

    fun prepare(expression: PreparedExpression) {
        prepared = expression
        state = ExpressionSendState.Ready(expression)
    }

    fun cancel() {
        if (state is ExpressionSendState.Sending) return
        prepared = null
        state = ExpressionSendState.Idle
    }

    suspend fun confirm(): ExpressionSendResult {
        val expression = mutex.withLock {
            when (state) {
                is ExpressionSendState.Sending -> return ExpressionSendResult.AlreadySending
                ExpressionSendState.Idle -> return ExpressionSendResult.NotPrepared
                is ExpressionSendState.Ready,
                is ExpressionSendState.Failed,
                -> requireNotNull(prepared).also { state = ExpressionSendState.Sending(it) }
            }
        }
        val result = runCatching { sender.send(expression) }
            .getOrElse { ExpressionSendResult.Failed(it.message ?: "图片发送失败") }
        mutex.withLock {
            if (result == ExpressionSendResult.Sent) {
                prepared = null
                state = ExpressionSendState.Idle
            } else {
                prepared = expression
                state = ExpressionSendState.Failed(expression, result)
            }
        }
        return result
    }
}
