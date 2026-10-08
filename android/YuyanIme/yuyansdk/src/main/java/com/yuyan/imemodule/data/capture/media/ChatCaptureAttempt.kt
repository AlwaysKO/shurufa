package com.yuyan.imemodule.data.capture.media

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import com.yuyan.imemodule.data.collect.GameWorkRuntime
import java.util.concurrent.atomic.AtomicReference

/** 单次同页取帧许可。帧已固定后只保留授权约束，不再读取导航后的页面。 */
internal class ChatCaptureAttempt(
    private val framePermission: () -> Boolean,
    private val scopeCurrent: () -> Boolean,
    private val authorized: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    val allowsSettledSendFrame: Boolean = false,
    private val onFrameRequest: () -> Boolean = { true },
    private val onFrameFailure: () -> Unit = {},
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ChatCaptureAttempt>
    // 只区分明确发送事件；同事件的取帧/持久化重试复用，普通检查不创建实例。
    val sendEventId: String? = if (allowsSettledSendFrame) java.util.UUID.randomUUID().toString() else null
    private val clearedSendIdentities = mutableSetOf<String>()
    @Synchronized fun claimSendHistoryReset(identity: String): Boolean =
        sendEventId != null && clearedSendIdentities.add(identity)
    private val acceptedAt = AtomicReference<Long?>(null)
    val capturedAtMillis: Long? get() = acceptedAt.get()
    val isAccepted: Boolean get() = capturedAtMillis != null
    fun canTakeFrame(): Boolean = !isAccepted && authorized() && scopeCurrent() &&
        framePermission() && GameWorkRuntime.isBackgroundAllowed()
    fun isAuthorized(): Boolean = authorized()
    internal fun beginFrameRequest(): Boolean = canTakeFrame() && onFrameRequest()
    internal fun frameRequestFailed() = onFrameFailure()
    internal fun acceptFrame(): Boolean = canTakeFrame() && acceptedAt.compareAndSet(null, clock())
}
