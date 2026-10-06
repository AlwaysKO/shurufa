package com.yuyan.imemodule.data.collect

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/** 信号只要求重新判断队列，不能绕过同步资格与退避直接发起网络请求。 */
internal class CollectorFlushSignal {
    private val signal=Channel<Unit>(Channel.CONFLATED)
    fun wake(){signal.trySend(Unit)}
    suspend fun awaitNext(pending:Boolean,imagesReady:Boolean):Boolean =
        withTimeoutOrNull(if(!pending)30*60_000L else if(imagesReady)1_000L else 30_000L){signal.receive();true} ?: false
}
