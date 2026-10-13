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
    STARTED, COMPLETED, CAPTURE_UNAVAILABLE, NODES_UNAVAILABLE, FRAME_INVALID, FRAME_STALE, READ_REJECTED,
    READ_FAILED, GROUP_MISMATCH, GROUP_UNVERIFIED, TARGET_UNCONFIRMED, ACTION_EXPIRED,
    ACTION_FAILED, RESULT_UNCONFIRMED, FLOW_STOPPED, FRAME_LIMIT, PROTECTED, TIMEOUT, FAILED,
    WAIT_CHAT_TITLE, WAIT_TITLE_UNVERIFIED, WAIT_INFO_TARGET, WAIT_GROUP_DETAILS,
    FALLBACK_STARTED, DETAILS_NAME_MISSING, DETAILS_NAME_MISMATCH, DETAILS_MEMBER_INVALID, DETAILS_MEMBER_CHANGED,
    ANCHOR_INELIGIBLE, ANCHOR_HEADER_MISMATCH, ANCHOR_SIGNATURE_MISMATCH, ANCHOR_MENU_MISMATCH, ANCHOR_SURFACE_MISMATCH,
    WAIT_CARD, WAIT_OPEN_PANEL, WAIT_OPEN_TARGET, WAIT_RESULT, WAIT_TITLE_CONFIRMATION, OCR_STALE_RECAPTURE,
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
                    onPageFacts: (SilentPacketReadDiagnostic) -> Unit = {},
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
            SilentPacketRunner(read = { reader.read(it, allowed) }).run(request, backend, allowed, mode, beforeCard, onPageFacts) { stage, event, elapsed ->
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
                    onPageFacts: (SilentPacketReadDiagnostic) -> Unit = {},
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
                execute(request, backend, allowed, mode, beforeCard, onPageFacts, ::trace)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (stopped: Abort) { stopped.status }
            catch (_: Exception) { trace(stage, SilentPacketEngineEvent.FAILED); "unknown" }
        }
        if (result == null) trace(stage, SilentPacketEngineEvent.TIMEOUT)
        return result ?: "unknown"
    }

    private suspend fun execute(request: PacketRequest, backend: SilentPacketBackend, allowed: () -> Boolean,
                                mode: SilentPacketMode, beforeCard: () -> Boolean,
                                onPageFacts: (SilentPacketReadDiagnostic) -> Unit,
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
        var flow = PacketFlow(request.candidate, startedAt, allowDetailsFallback = true)
        var displayId: Int? = null
        var bounds: IntRect? = null
        var lastFrameId = 0L
        var frames = 0
        var cardReserved = false
        var groupVerified = false
        var infoRequested = false
        var staleDiscards = 0
        var pendingWrongTitle: String? = null
        var pendingWrongCapturedAt = 0L
        var lastWait: Pair<SilentPacketEngineStage, SilentPacketEngineEvent>? = null
        var flowStage = SilentPacketEngineStage.GROUP_CHECK
        data class Observation(val snapshot: PacketVisualSnapshot, val frameId: Long)
        data class ChatAnchor(val header: String, val signature: String, val menu: IntRect,
                              val displayId: Int, val bounds: IntRect, val originX: Int, val originY: Int)
        var fallbackAnchor: ChatAnchor? = null
        fun eligibleFallbackInfo(snapshot: PacketVisualSnapshot): Boolean {
            val page = snapshot.match.page
            val facts = snapshot.sourceFacts ?: return false
            return !page.chatName.isNullOrBlank() && page.chatName !in setOf("微信", "WeChat") &&
                page.chatInfoButton == "visual:info" && page.uniqueChatToolbar && !page.packetPanel && page.result == null &&
                !page.verifiedGroupDetails && page.groupDetailsName == null && page.groupDetailsMemberCount == null &&
                snapshot.match.targets.keys.count { it.startsWith("visual:info") } == 1 &&
                snapshot.match.targets["visual:info"] != null && !snapshot.signatures["visual:info"].isNullOrBlank() &&
                facts.menuDotCount == 1 &&
                !facts.bottomWechatTabsPresent && !facts.wechatTitleLabelPresent && !facts.loginPromptPresent &&
                !facts.loadingPromptPresent && !facts.networkErrorPresent && !facts.permissionPromptPresent
        }
        fun restoredAnchor(snapshot: PacketVisualSnapshot): Boolean {
            val anchor = fallbackAnchor ?: return false
            val reason = when {
                !eligibleFallbackInfo(snapshot) -> SilentPacketEngineEvent.ANCHOR_INELIGIBLE
                snapshot.match.page.chatName != anchor.header -> SilentPacketEngineEvent.ANCHOR_HEADER_MISMATCH
                snapshot.signatures["visual:info"] != anchor.signature -> SilentPacketEngineEvent.ANCHOR_SIGNATURE_MISMATCH
                snapshot.match.targets["visual:info"] != anchor.menu -> SilentPacketEngineEvent.ANCHOR_MENU_MISMATCH
                snapshot.windowId != anchor.displayId || snapshot.bounds != anchor.bounds ||
                    snapshot.originX != anchor.originX || snapshot.originY != anchor.originY ->
                    SilentPacketEngineEvent.ANCHOR_SURFACE_MISMATCH
                else -> null
            }
            if (reason != null) trace(SilentPacketEngineStage.GROUP_BACK, reason)
            return reason == null
        }
        fun detailsFailure(first: PacketPage, second: PacketPage? = null): SilentPacketEngineEvent? = when {
            first.groupDetailsName.isNullOrBlank() || second != null && second.groupDetailsName.isNullOrBlank() ->
                SilentPacketEngineEvent.DETAILS_NAME_MISSING
            first.groupDetailsName != request.candidate.chatName ||
                second != null && second.groupDetailsName != request.candidate.chatName ->
                SilentPacketEngineEvent.DETAILS_NAME_MISMATCH
            (first.groupDetailsMemberCount ?: 0) < 2 || second != null && (second.groupDetailsMemberCount ?: 0) < 2 ->
                SilentPacketEngineEvent.DETAILS_MEMBER_INVALID
            second != null && first.groupDetailsMemberCount != second.groupDetailsMemberCount ->
                SilentPacketEngineEvent.DETAILS_MEMBER_CHANGED
            else -> null
        }
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
            while (true) {
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
                    val readStarted = clock()
                    val snapshot = try { read(frame) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { unknown(SilentPacketEngineStage.OCR, SilentPacketEngineEvent.READ_FAILED) }
                    trace(SilentPacketEngineStage.OCR, SilentPacketEngineEvent.COMPLETED)
                    guard()
                    if (snapshot == null) unknown(SilentPacketEngineStage.OCR, SilentPacketEngineEvent.READ_REJECTED)
                    if (snapshot.windowId != frame.displayId || snapshot.bounds != area ||
                        snapshot.originX != 0 || snapshot.originY != 0 || snapshot.capturedAt != frame.capturedAt)
                        unknown(SilentPacketEngineStage.OCR, SilentPacketEngineEvent.FRAME_INVALID)
                    if (clock() - snapshot.capturedAt !in 0..1_000) {
                        // 只丢弃OCR耗时导致的过期帧，不重放动作，不把过期证据发布给诊断或Flow。
                        if (!infoRequested && !cardReserved && staleDiscards < 2 &&
                            clock() - startedAt in 0 until PACKET_TIMEOUT && clock() >= snapshot.capturedAt) {
                            staleDiscards++
                            trace(SilentPacketEngineStage.OCR, SilentPacketEngineEvent.OCR_STALE_RECAPTURE)
                            continue
                        }
                        unknown(SilentPacketEngineStage.OCR, SilentPacketEngineEvent.FRAME_STALE)
                    }
                    // 全部来源与1秒时效校验之后，只向诊断发布固定事实，不传文字/目标/图片。
                    val page = snapshot.match.page
                    val facts = SilentPacketReadDiagnostic(SilentPacketReadResult.VALID,
                        (clock() - readStarted).coerceAtLeast(0), clock() - snapshot.capturedAt,
                        SilentPacketPageFacts(!page.chatName.isNullOrBlank(), page.groupChat,
                            page.chatInfoButton?.let { it in snapshot.match.targets } == true,
                            page.verifiedGroupDetails, page.packetPanel, page.cards.size, page.uniqueChatToolbar,
                            !page.groupDetailsName.isNullOrBlank(),
                            page.groupDetailsName?.let { it == request.candidate.chatName },
                            page.groupDetailsMemberCount), snapshot.sourceFacts,
                        silentPacketGroupComparison(request.candidate.chatName, page.chatName))
                    try { onPageFacts(facts) } catch (_: Exception) { }
                    guard()
                    return Observation(snapshot, frame.frameId)
                } finally { if (!frame.bitmap.isRecycled) frame.bitmap.recycle() }
            }
        }
        fun sameSurface(first: PacketVisualSnapshot, second: PacketVisualSnapshot): Boolean =
            first.windowId == second.windowId && first.bounds == second.bounds &&
                first.originX == second.originX && first.originY == second.originY &&
                clock() - first.capturedAt in 0..4_000 && clock() - second.capturedAt in 0..1_000

        var current = capture()
        trace(SilentPacketEngineStage.GROUP_CHECK, SilentPacketEngineEvent.STARTED)
        val initial = current.snapshot.match.page
        if (mode == SilentPacketMode.PROBE) {
            if (initial.chatName != null && initial.chatName != request.candidate.chatName) {
                trace(SilentPacketEngineStage.GROUP_CHECK, SilentPacketEngineEvent.GROUP_MISMATCH)
                return "mismatch"
            }
            return if (initial.chatName == request.candidate.chatName &&
                (initial.groupChat || request.candidate.confirmedGroup) && initial.cards.isNotEmpty())
                "probe_candidate" else "probe_no_candidate"
        }
        while (true) {
            guard()
            val first = current.snapshot
            val name = first.match.page.chatName
            if (!infoRequested && !cardReserved && !flow.usesDetailsFallback &&
                name != null && name != request.candidate.chatName && eligibleFallbackInfo(first) &&
                flow.beginDetailsFallback(name)) {
                fallbackAnchor = ChatAnchor(name, first.signatures.getValue("visual:info"),
                    first.match.targets.getValue("visual:info"), first.windowId, first.bounds, first.originX, first.originY)
                trace(SilentPacketEngineStage.GROUP_CHECK, SilentPacketEngineEvent.FALLBACK_STARTED)
            }
            if (!infoRequested && !cardReserved && !flow.usesDetailsFallback && name != null && name != request.candidate.chatName) {
                if (clock() - startedAt !in 0 until PACKET_TIMEOUT)
                    unknown(SilentPacketEngineStage.GROUP_CHECK, SilentPacketEngineEvent.TIMEOUT)
                if (name == pendingWrongTitle && clock() - pendingWrongCapturedAt in 0..4_000) {
                    trace(SilentPacketEngineStage.GROUP_CHECK, SilentPacketEngineEvent.GROUP_MISMATCH)
                    return "mismatch"
                }
                pendingWrongTitle = name
                pendingWrongCapturedAt = first.capturedAt
                val waiting = SilentPacketEngineStage.GROUP_CHECK to SilentPacketEngineEvent.WAIT_TITLE_CONFIRMATION
                if (waiting != lastWait) { trace(waiting.first, waiting.second); lastWait = waiting }
                pause(350)
                current = capture()
                continue
            }
            pendingWrongTitle = null
            when (val action = flow.step(first.match.page, clock())) {
                PacketAction.Stop -> {
                    if (flow.usesDetailsFallback && infoRequested && !groupVerified && first.match.page.verifiedGroupDetails)
                        detailsFailure(first.match.page)?.let { trace(SilentPacketEngineStage.GROUP_CHECK, it) }
                    unknown(flowStage, if (clock() - startedAt >= PACKET_TIMEOUT)
                    SilentPacketEngineEvent.TIMEOUT else if (first.match.page.chatName != null &&
                        first.match.page.chatName != request.candidate.chatName) SilentPacketEngineEvent.GROUP_MISMATCH
                    else if (!groupVerified) SilentPacketEngineEvent.GROUP_UNVERIFIED
                    else SilentPacketEngineEvent.FLOW_STOPPED)
                }
                PacketAction.Wait -> {
                    // 仅解释当前等待，不改变PacketFlow或给任何目标新增点击资格。
                    val page = first.match.page
                    val reason = when (flowStage) {
                        SilentPacketEngineStage.GROUP_CHECK -> when {
                            infoRequested -> SilentPacketEngineEvent.WAIT_GROUP_DETAILS
                            page.chatName == null && (first.sourceFacts?.titleCandidateCount ?: 0) > 0 ->
                                SilentPacketEngineEvent.WAIT_TITLE_UNVERIFIED
                            page.chatName == null -> SilentPacketEngineEvent.WAIT_CHAT_TITLE
                            else -> SilentPacketEngineEvent.WAIT_INFO_TARGET
                        }
                        SilentPacketEngineStage.CARD_CLICK -> if (page.chatName == null)
                            SilentPacketEngineEvent.WAIT_CHAT_TITLE else SilentPacketEngineEvent.WAIT_CARD
                        SilentPacketEngineStage.OPEN_CLICK -> if (!page.packetPanel)
                            SilentPacketEngineEvent.WAIT_OPEN_PANEL else SilentPacketEngineEvent.WAIT_OPEN_TARGET
                        else -> SilentPacketEngineEvent.WAIT_RESULT
                    }
                    val waiting = flowStage to reason
                    if (waiting != lastWait) { trace(flowStage, reason); lastWait = waiting }
                }
                PacketAction.Back -> {
                    pause(350)
                    val second = capture().snapshot
                    guard()
                    if (!flow.canClick(clock()) || !sameSurface(first, second) ||
                        !first.match.page.verifiedGroupDetails || !second.match.page.verifiedGroupDetails ||
                        first.match.page.packetPanel || second.match.page.packetPanel)
                        unknown(SilentPacketEngineStage.GROUP_BACK, SilentPacketEngineEvent.GROUP_UNVERIFIED)
                    if (flow.usesDetailsFallback &&
                        (first.match.page.groupDetailsName != request.candidate.chatName ||
                            second.match.page.groupDetailsName != request.candidate.chatName ||
                            (first.match.page.groupDetailsMemberCount ?: 0) < 2 ||
                            first.match.page.groupDetailsMemberCount != second.match.page.groupDetailsMemberCount)) {
                        detailsFailure(first.match.page, second.match.page)?.let { trace(SilentPacketEngineStage.GROUP_BACK, it) }
                        unknown(SilentPacketEngineStage.GROUP_BACK, SilentPacketEngineEvent.GROUP_UNVERIFIED)
                    }
                    trace(SilentPacketEngineStage.GROUP_BACK, SilentPacketEngineEvent.STARTED)
                    if (!backend.back()) unknown(SilentPacketEngineStage.GROUP_BACK, SilentPacketEngineEvent.ACTION_FAILED)
                    trace(SilentPacketEngineStage.GROUP_BACK, SilentPacketEngineEvent.COMPLETED)
                    groupVerified = true
                    flowStage = SilentPacketEngineStage.CARD_CLICK
                    if (flow.usesDetailsFallback) {
                        pause(350)
                        val restoredFirst = capture().snapshot
                        pause(350)
                        val restoredSecond = capture()
                        guard()
                        val sameRestoredSurface = sameSurface(restoredFirst, restoredSecond.snapshot)
                        if (!sameRestoredSurface) trace(SilentPacketEngineStage.GROUP_BACK,
                            SilentPacketEngineEvent.ANCHOR_SURFACE_MISMATCH)
                        if (!flow.canClick(clock()) || !sameRestoredSurface ||
                            !restoredAnchor(restoredFirst) || !restoredAnchor(restoredSecond.snapshot) ||
                            !flow.restoreVerifiedChat())
                            unknown(SilentPacketEngineStage.GROUP_BACK, SilentPacketEngineEvent.GROUP_UNVERIFIED)
                        current = restoredSecond
                        continue
                    }
                }
                is PacketAction.Click -> {
                    val clickStage = when {
                        action.id.startsWith("visual:card:") -> SilentPacketEngineStage.CARD_CLICK
                        action.id == "visual:open" -> SilentPacketEngineStage.OPEN_CLICK
                        else -> SilentPacketEngineStage.INFO_CLICK
                    }
                    pause(350)
                    val confirmation = capture()
                    if (clickStage == SilentPacketEngineStage.INFO_CLICK && !infoRequested && !cardReserved && !flow.usesDetailsFallback &&
                        sameSurface(first, confirmation.snapshot) && confirmation.snapshot.match.page.chatName != null &&
                        confirmation.snapshot.match.page.chatName != request.candidate.chatName) {
                        // Flow已产生但尚未提交INFO动作；重置纯未提交状态，原开始时间和截止不变。
                        flow = PacketFlow(request.candidate, startedAt, allowDetailsFallback = true)
                        current = confirmation
                        continue
                    }
                    val target = confirmedPacketTarget(first, confirmation.snapshot, action.id, clock())
                        ?: unknown(clickStage, SilentPacketEngineEvent.TARGET_UNCONFIRMED)
                    if (flow.usesDetailsFallback && (clickStage == SilentPacketEngineStage.INFO_CLICK ||
                            clickStage == SilentPacketEngineStage.CARD_CLICK) &&
                        (!restoredAnchor(first) || !restoredAnchor(confirmation.snapshot)))
                        unknown(clickStage, SilentPacketEngineEvent.TARGET_UNCONFIRMED)
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
                    if (clickStage == SilentPacketEngineStage.INFO_CLICK) infoRequested = true
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
