package com.yuyan.imemodule.data.collect

import android.os.SystemClock
import java.util.concurrent.Executor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import okhttp3.Interceptor
import okhttp3.Call
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.io.IOException

/** 游戏优先与打字空闲分别判断，避免改变红包的原有操作策略。 */
object GameWorkRuntime {
    private val gate = GameWorkGate(SystemClock::elapsedRealtime, Dispatchers.IO.asExecutor())
    fun isBackgroundAllowed(): Boolean = gate.isAllowed()
    fun requireBackgroundAllowed() {
        if (!isBackgroundAllowed()) throw GameWorkPausedException()
    }
    val interceptor: Interceptor get() = gate.interceptor
    internal fun setGaming(value: Boolean) = gate.setGaming(value)
}

class GameWorkPausedException : IOException("Background work paused for game")

internal class GameWorkGate(private val clock: () -> Long, executor: Executor) {
    @Volatile private var gaming = false
    @Volatile private var resumeAt = 0L
    private val calls = InputPriorityCancellation<Call>(executor) { it.cancel() }
    fun isAllowed(): Boolean = !gaming && clock() >= resumeAt
    @Synchronized fun setGaming(value: Boolean) {
        if (gaming == value) return
        if (value) {
            gaming = true
            calls.request()
        } else {
            resumeAt = clock() + 3_000
            gaming = false
        }
    }
    val interceptor = Interceptor { chain ->
        val call = chain.call()
        val token = calls.token()
        if (!isAllowed()) throw IOException("Background work paused for game")
        calls.track(call, token)
        try {
            if (!isAllowed() || calls.token() != token || call.isCanceled())
                throw IOException("Background work paused for game")
            val response = chain.proceed(chain.request())
            val body = response.body
            if (body == null) { calls.finish(call); response }
            else response.newBuilder().body(object : ResponseBody() {
                private val source = object : ForwardingSource(body.source()) {
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        try {
                            if (!isAllowed() || calls.token() != token || call.isCanceled())
                                throw IOException("Background work paused for game")
                            return super.read(sink, byteCount).also { if (it == -1L) calls.finish(call) }
                        } catch (error: Exception) { calls.finish(call); throw error }
                    }
                    override fun close() { try { super.close() } finally { calls.finish(call) } }
                }.buffer()
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun source() = source
            }).build()
        } catch (error: Exception) { calls.finish(call); throw error }
    }
}
