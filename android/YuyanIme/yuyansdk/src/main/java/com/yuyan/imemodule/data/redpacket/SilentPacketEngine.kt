package com.yuyan.imemodule.data.redpacket

import android.graphics.Bitmap
import android.os.SystemClock
import com.yuyan.imemodule.data.capture.ui.IntRect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal enum class SilentPacketMode { OFF, PROBE, AUTO }
internal enum class SilentPacketEngineStage { PREPARE, CAPTURE, OCR, GROUP_CHECK, INFO_CLICK, GROUP_BACK, CARD_CLICK, OPEN_CLICK, RESULT_CHECK }
internal enum class SilentPacketEngineEvent {
    STARTED, COMPLETED, CAPTURE_UNAVAILABLE, FRAME_INVALID, FRAME_STALE, READ_REJECTED,
    READ_FAILED, GROUP_MISMATCH, GROUP_UNVERIFIED, TARGET_UNCONFIRMED, ACTION_EXPIRED,
    ACTION_FAILED, RESULT_UNCONFIRMED, FLOW_STOPPED, FRAME_LIMIT, PROTECTED, TIMEOUT, FAILED,
}

/** capture 移交 Bitmap 所有权；tap 必须只作用于副屏并核验尚未失效的 frameId。 */
internal interface SilentPacketBackend {
    fun capture(): SilentPacketFrame?
    fun tap(x: Int, y: Int, frameId: Long): Boolean
    fun back(): Boolean
}

internal data class SilentPacketFrame(val bitmap: Bitmap, val frameId: Long, val capturedAt: Long, val displayId: Int)

internal object SilentPacketEngine {
    suspend fun run(request: PacketRequest, backend: SilentPacketBackend, allowed: () -> Boolean,
                    mode: SilentPacketMode = SilentPacketMode.PROBE, beforeCard: () -> Boolean = { true },
                    onTrace: (SilentPacketEngineStage, SilentPacketEngineEvent, Long) -> Unit = { _, _, _ -> }): String = withContext(Dispatchers.IO) {
        if (mode == SilentPacketMode.OFF) return@withContext "off"
        val reader = SilentPacketReader()
        val startedAt = SystemClock.uptimeMillis()
        fun prepareTrace(event: SilentPacketEngineEvent) {
            try { onTrace(SilentPacketEngineStage.PREPARE, event, (SystemClock.uptimeMillis() - startedAt).coerceAtLeast(0)) }
            catch (_: Exception) { }
        }
        try {
            prepareTrace(SilentPacketEngineEvent.STARTED)
            val prepared = try { reader.prepare(allowed) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    prepareTrace(SilentPacketEngineEvent.READ_FAILED)
                    return@withContext "unknown"
                }
            if (!prepared) {
                prepareTrace(SilentPacketEngineEvent.PROTECTED)
                return@withContext "protected"
            }
            prepareTrace(SilentPacketEngineEvent.COMPLETED)
            val preparedMs = (SystemClock.uptimeMillis() - startedAt).coerceAtLeast(0)
            SilentPacketRunner(read = { reader.read(it, allowed) }).run(request, backend, allowed, mode, beforeCard) { stage, event, elapsed ->
                onTrace(stage, event, preparedMs + elapsed)
            }
        } finally { reader.close() }
    }
}

internal class SilentPacketRunner(
    private val read: suspend (SilentPacketFrame) -> PacketVisualSnapshot?,
    private val clock: () -> Long = SystemClock::uptimeMillis,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private class Abort(val status: String) : RuntimeException()

    suspend fun run(request: PacketRequest, backend: SilentPacketBackend, allowed: () -> Boolean,
                    mode: SilentPacketMode = SilentPacketMode.PROBE, beforeCard: () -> Boolean = { true },
                    onTrace: (SilentPacketEngineStage, SilentPacketEngineEvent, Long) -> Unit = { _, _, _ -> }): String {
        if (mode == SilentPacketMode.OFF) return "off"
        val traceStartedAt = clock()
        var stage = SilentPacketEngineStage.CAPTURE
        fun trace(next: SilentPacketEngineStage, event: SilentPacketEngineEvent) {
            stage = next
            // 诊断只接受固定枚举和耗时，观察者异常不能影响领取决策。
            try { onTrace(next, event, (clock() - traceStartedAt).coerceAtLeast(0)) }
            catch (_: Exception) { }
        }
        val result = withTimeoutOrNull(PACKET_TIMEOUT + 5_000) {
            try {
                execute(request, backend, allowed, mode, beforeCard, ::trace)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (stopped: Abort) { stopped.status }
            catch (_: Exception) { trace(stage, SilentPacketEngineEvent.FAILED); "unknown" }
        }
        if (result == null) trace(stage, SilentPacketEngineEvent.TIMEOUT)
        return result ?: "unknown"
    }

    private suspend fun execute(request: PacketRequest, backend: SilentPacketBackend, allowed: () -> Boolean,
                                mode: SilentPacketMode, beforeCard: () -> Boolean,
                                trace: (SilentPacketEngineStage, SilentPacketEngineEvent) -> Unit): String {
        fun unknown(stage: SilentPacketEngineStage, event: SilentPacketEngineEvent): Nothing {
            trace(stage, event)
            throw Abort("unknown")
        }
        suspend fun guard() {
            currentCoroutineContext().ensureActive()
            if (!allowed()) { trace(SilentPacketEngineStage.CAPTURE, SilentPacketEngineEvent.PROTECTED); throw Abort("protected") }
        }
        guard()
        if (!request.isValidAt(wallClock())) return "stale_request"
        val startedAt = clock()
        val flow = PacketFlow(request.candidate, startedAt)
        var displayId: Int? = null
        var bounds: IntRect? = null
        var lastFrameId = 0L
        var frames = 0
        var cardReserved = false
        var groupVerified = false
        var flowStage = SilentPacketEngineStage.GROUP_CHECK
        data class Observation(val snapshot: PacketVisualSnapshot, val frameId: Long)
        suspend fun availableFrame(): SilentPacketFrame {
            val waitingAt = clock()
            for (attempt in 0..10) {
                guard()
                if (clock() - startedAt !in 0 until PACKET_TIMEOUT + 5_000)
                    unknown(SilentPacketEngineStage.CAPTURE, SilentPacketEngineEvent.TIMEOUT)
                backend.capture()?.let { return it }
                val remaining = 1_000 - (clock() - waitingAt)
                if (attempt == 10 || remaining <= 0)
                    unknown(SilentPacketEngineStage.CAPTURE, SilentPacketEngineEvent.CAPTURE_UNAVAILABLE)
                pause(minOf(100, remaining))
            }
            unknown(SilentPacketEngineStage.CAPTURE, SilentPacketEngineEvent.CAPTURE_UNAVAILABLE)
        }
        suspend fun capture(): Observation {
            guard()
            trace(SilentPacketEngineStage.CAPTURE, SilentPacketEngineEvent.STARTED)
            if (++frames > 48) unknown(SilentPacketEngineStage.CAPTURE, SilentPacketEngineEvent.FRAME_LIMIT)
            // 新 buffer 尚未到达时只等待截图，不重复已提交动作或退回物理主屏。
            val frame = availableFrame()
            try {
                guard()
                if (frame.bitmap.isRecycled || frame.displayId <= 0 || frame.frameId <= lastFrameId ||
                    frame.bitmap.width <= 0 || frame.bitmap.height <= 0)
                    unknown(SilentPacketEngineStage.CAPTURE, SilentPacketEngineEvent.FRAME_INVALID)
                if (clock() - frame.capturedAt !in 0..1_000)
                    unknown(SilentPacketEngineStage.CAPTURE, SilentPacketEngineEvent.FRAME_STALE)
                val area = IntRect(0, 0, frame.bitmap.width, frame.bitmap.height)
                if ((displayId != null && displayId != frame.displayId) || (bounds != null && bounds != area))
                    unknown(SilentPacketEngineStage.CAPTURE, SilentPacketEngineEvent.FRAME_INVALID)
                displayId = frame.displayId
                bounds = area
                lastFrameId = frame.frameId
                trace(SilentPacketEngineStage.CAPTURE, SilentPacketEngineEvent.COMPLETED)
                trace(SilentPacketEngineStage.OCR, SilentPacketEngineEvent.STARTED)
                val snapshot = try { read(frame) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { unknown(SilentPacketEngineStage.OCR, SilentPacketEngineEvent.READ_FAILED) }
                trace(SilentPacketEngineStage.OCR, SilentPacketEngineEvent.COMPLETED)
                guard()
                if (snapshot == null) unknown(SilentPacketEngineStage.OCR, SilentPacketEngineEvent.READ_REJECTED)
                if (snapshot.windowId != frame.displayId || snapshot.bounds != area ||
                    snapshot.originX != 0 || snapshot.originY != 0 || snapshot.capturedAt != frame.capturedAt)
                    unknown(SilentPacketEngineStage.OCR, SilentPacketEngineEvent.FRAME_INVALID)
                if (clock() - snapshot.capturedAt !in 0..1_000)
                    unknown(SilentPacketEngineStage.OCR, SilentPacketEngineEvent.FRAME_STALE)
                return Observation(snapshot, frame.frameId)
            } finally { if (!frame.bitmap.isRecycled) frame.bitmap.recycle() }
        }
        fun sameSurface(first: PacketVisualSnapshot, second: PacketVisualSnapshot): Boolean =
            first.windowId == second.windowId && first.bounds == second.bounds &&
                first.originX == second.originX && first.originY == second.originY &&
                clock() - first.capturedAt in 0..4_000 && clock() - second.capturedAt in 0..1_000

        var current = capture()
        trace(SilentPacketEngineStage.GROUP_CHECK, SilentPacketEngineEvent.STARTED)
        val initial = current.snapshot.match.page
        if (initial.chatName != null && initial.chatName != request.candidate.chatName) {
            trace(SilentPacketEngineStage.GROUP_CHECK, SilentPacketEngineEvent.GROUP_MISMATCH)
            return "mismatch"
        }
        if (mode == SilentPacketMode.PROBE) return if (initial.chatName == request.candidate.chatName &&
            (initial.groupChat || request.candidate.confirmedGroup) && initial.cards.isNotEmpty())
            "probe_candidate" else "probe_no_candidate"
        while (true) {
            guard()
            val first = current.snapshot
            when (val action = flow.step(first.match.page, clock())) {
                PacketAction.Stop -> unknown(flowStage, if (clock() - startedAt >= PACKET_TIMEOUT)
                    SilentPacketEngineEvent.TIMEOUT else if (!groupVerified) SilentPacketEngineEvent.GROUP_UNVERIFIED
                    else SilentPacketEngineEvent.FLOW_STOPPED)
                PacketAction.Wait -> trace(flowStage, SilentPacketEngineEvent.STARTED)
                PacketAction.Back -> {
                    pause(350)
                    val second = capture().snapshot
                    guard()
                    if (!flow.canClick(clock()) || !sameSurface(first, second) ||
                        !first.match.page.verifiedGroupDetails || !second.match.page.verifiedGroupDetails ||
                        first.match.page.packetPanel || second.match.page.packetPanel)
                        unknown(SilentPacketEngineStage.GROUP_BACK, SilentPacketEngineEvent.GROUP_UNVERIFIED)
                    trace(SilentPacketEngineStage.GROUP_BACK, SilentPacketEngineEvent.STARTED)
                    if (!backend.back()) unknown(SilentPacketEngineStage.GROUP_BACK, SilentPacketEngineEvent.ACTION_FAILED)
                    trace(SilentPacketEngineStage.GROUP_BACK, SilentPacketEngineEvent.COMPLETED)
                    groupVerified = true
                    flowStage = SilentPacketEngineStage.CARD_CLICK
                }
                is PacketAction.Click -> {
                    val clickStage = when {
                        action.id.startsWith("visual:card:") -> SilentPacketEngineStage.CARD_CLICK
                        action.id == "visual:open" -> SilentPacketEngineStage.OPEN_CLICK
                        else -> SilentPacketEngineStage.INFO_CLICK
                    }
                    pause(350)
                    val confirmation = capture()
                    val target = confirmedPacketTarget(first, confirmation.snapshot, action.id, clock())
                        ?: unknown(clickStage, SilentPacketEngineEvent.TARGET_UNCONFIRMED)
                    guard()
                    if (!flow.canClick(clock())) unknown(clickStage, SilentPacketEngineEvent.ACTION_EXPIRED)
                    if (action.id.startsWith("visual:card:")) {
                        if (cardReserved) unknown(clickStage, SilentPacketEngineEvent.FLOW_STOPPED)
                        cardReserved = true
                        if (!beforeCard()) return "duplicate"
                        guard()
                        if (!flow.canClick(clock()) || clock() - confirmation.snapshot.capturedAt !in 0..1_000)
                            unknown(clickStage, SilentPacketEngineEvent.ACTION_EXPIRED)
                    }
                    // PacketFlow 在返回动作时已经推进阶段，任何失败/异常都不会重放该点击。
                    trace(clickStage, SilentPacketEngineEvent.STARTED)
                    if (!backend.tap((target.left + target.right) / 2, (target.top + target.bottom) / 2,
                            confirmation.frameId)) unknown(clickStage, SilentPacketEngineEvent.ACTION_FAILED)
                    trace(clickStage, SilentPacketEngineEvent.COMPLETED)
                    flowStage = when (clickStage) {
                        SilentPacketEngineStage.INFO_CLICK -> SilentPacketEngineStage.GROUP_CHECK
                        SilentPacketEngineStage.CARD_CLICK -> SilentPacketEngineStage.OPEN_CLICK
                        else -> SilentPacketEngineStage.RESULT_CHECK
                    }
                }
                is PacketAction.Finish -> {
                    trace(SilentPacketEngineStage.RESULT_CHECK, SilentPacketEngineEvent.STARTED)
                    pause(350)
                    val second = capture().snapshot
                    guard()
                    if (!sameSurface(first, second) || !second.match.page.packetPanel ||
                        second.match.page.result != action.result)
                        unknown(SilentPacketEngineStage.RESULT_CHECK, SilentPacketEngineEvent.RESULT_UNCONFIRMED)
                    trace(SilentPacketEngineStage.RESULT_CHECK, SilentPacketEngineEvent.COMPLETED)
                    return when (action.result) {
                        "已领取" -> "claimed"
                        "已过期" -> "expired"
                        "已被领完" -> "exhausted"
                        else -> "unknown"
                    }
                }
            }
            pause(350)
            current = capture()
        }
    }
}
