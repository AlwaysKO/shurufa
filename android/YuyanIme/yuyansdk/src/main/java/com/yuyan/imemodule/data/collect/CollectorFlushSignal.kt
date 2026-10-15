package com.yuyan.imemodule.data.collect

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/** 信号只要求重新判断队列，不能绕过同步资格与退避直接发起网络请求。 */
internal class CollectorFlushSignal {
    private val signal=Channel<Unit>(Channel.CONFLATED)
    /** 存储/资格查询暂时失败不杀死常驻协程；新事件可打断三十分钟兜底，取消仍立即退出。 */
    suspend fun guardedCycle(block: suspend () -> Unit) {
        try { block() }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { awaitNext(false, false) }
    }
    fun wake(){signal.trySend(Unit)}
    suspend fun awaitNext(pending:Boolean,imagesReady:Boolean,nextDelayMillis:Long?=null):Boolean =
        withTimeoutOrNull(if(!pending)30*60_000L else if(imagesReady)1_000L else nextDelayMillis?.coerceAtLeast(5_000L) ?: 30_000L){signal.receive();true} ?: false
}
