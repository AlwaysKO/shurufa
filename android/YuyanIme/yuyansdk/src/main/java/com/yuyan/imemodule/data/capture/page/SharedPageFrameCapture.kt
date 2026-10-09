package com.yuyan.imemodule.data.capture.page

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class PageFrameCaptureKey(
    val packageName: String, val windowId: Int, val navigation: Long, val consent: Long, val policy: Long,
)
internal data class ObservedPageFrame(val frame: PageFrame, val elapsed: Long, val wall: Long)

/** 两类页面候选共用同一导航的物理帧；短暂保留一个已安全分类的编码图，不跨导航或授权复用。 */
internal class SharedPageFrameCapture(private val elapsed: () -> Long, private val wall: () -> Long) {
    private data class Cached(val key: PageFrameCaptureKey, val value: ObservedPageFrame, val completed: Long)
    private val mutex = Mutex()
    @Volatile private var cached: Cached? = null

    fun clear() { cached = null }

    suspend fun capture(key: PageFrameCaptureKey, current: () -> Boolean,
        read: suspend ((PageFrame) -> Unit) -> Unit, onAccepted: (ObservedPageFrame) -> Unit) {
        mutex.withLock {
            if (!current()) return
            val previous = cached
            if (previous != null && previous.key == key && elapsed() - previous.completed in 0..1500) {
                if (current()) onAccepted(previous.value)
                return
            }
            cached = null
            val observedElapsed = elapsed()
            val observedWall = wall()
            var observed: ObservedPageFrame? = null
            read { frame ->
                check(observed == null)
                ObservedPageFrame(frame, observedElapsed, observedWall).also {
                    observed = it
                    // 接受回调保留原不可取消落盘交接；取消不使已经接受的合法图片丢失。
                    onAccepted(it)
                }
            }
            if (current()) observed?.let { cached = Cached(key, it, elapsed()) }
        }
    }
}
