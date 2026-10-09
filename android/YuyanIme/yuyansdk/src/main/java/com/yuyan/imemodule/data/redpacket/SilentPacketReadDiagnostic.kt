package com.yuyan.imemodule.data.redpacket

import android.os.SystemClock
import com.yuyan.imemodule.data.capture.ui.IntRect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal enum class SilentPacketReadResult { VALID, FRAME_STALE, FRAME_INVALID, READ_REJECTED, READ_FAILED, PROTECTED }
internal data class SilentPacketReadDiagnostic(val result: SilentPacketReadResult, val elapsedMs: Long, val frameAgeMs: Long)

/** 只返回识别状态和耗时；不输出文字、图片或窗口身份，也不放宽点击使用的1秒时效。 */
internal suspend fun inspectSilentPacketFrame(frame: SilentPacketFrame, allowed: () -> Boolean,
    read: suspend (SilentPacketFrame) -> PacketVisualSnapshot?, clock: () -> Long = SystemClock::uptimeMillis
): SilentPacketReadDiagnostic {
    val started = clock()
    fun result(value: SilentPacketReadResult, now: Long = clock()): SilentPacketReadDiagnostic {
        return SilentPacketReadDiagnostic(value, (now - started).coerceAtLeast(0), now - frame.capturedAt)
    }
    try {
        currentCoroutineContext().ensureActive()
        if (!allowed()) return result(SilentPacketReadResult.PROTECTED)
        val bitmap = frame.bitmap
        if (bitmap.isRecycled || frame.displayId <= 0 || frame.frameId <= 0 ||
            bitmap.width !in 1..4096 || bitmap.height !in 1..8192 ||
            bitmap.width.toLong() * bitmap.height > 8_388_608)
            return result(SilentPacketReadResult.FRAME_INVALID)
        if (started - frame.capturedAt !in 0..1_000)
            return result(SilentPacketReadResult.FRAME_STALE)
        // Reader会回收输入Bitmap，提前保留用于一致性校验的尺寸。
        val bounds = IntRect(0, 0, bitmap.width, bitmap.height)
        val snapshot = read(frame)
        currentCoroutineContext().ensureActive()
        if (!allowed()) return result(SilentPacketReadResult.PROTECTED)
        val now = clock()
        if (now < started || now - frame.capturedAt !in 0..1_000)
            return result(SilentPacketReadResult.FRAME_STALE, now)
        if (snapshot == null || snapshot.windowId != frame.displayId || snapshot.bounds != bounds ||
            snapshot.originX != 0 || snapshot.originY != 0 || snapshot.capturedAt != frame.capturedAt)
            return result(SilentPacketReadResult.READ_REJECTED, now)
        return result(SilentPacketReadResult.VALID, now)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        return result(SilentPacketReadResult.READ_FAILED)
    } finally {
        if (!frame.bitmap.isRecycled) frame.bitmap.recycle()
    }
}
