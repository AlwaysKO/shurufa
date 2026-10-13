package com.yuyan.imemodule.data.redpacket

import android.os.SystemClock
import com.yuyan.imemodule.data.capture.ui.IntRect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal interface NativePacketBackend {
    fun observe(): NativePacketSnapshot?
    fun tap(x: Int, y: Int, frameId: Long): Boolean
    fun back(): Boolean
}

/** Native text stays in memory. The notification route is never sufficient proof of a chat. */
internal class NativePacketRunner(
    private val clock: () -> Long = SystemClock::uptimeMillis,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private class Abort(val outcome: String) : RuntimeException()
    private enum class Phase { CHAT, DETAILS, OPEN, RESULT }

    suspend fun run(request: PacketRequest, backend: NativePacketBackend, allowed: () -> Boolean,
        mode: SilentPacketMode = SilentPacketMode.PROBE, beforeCard: () -> Boolean = { true },
        onTrace: (SilentPacketEngineStage, SilentPacketEngineEvent, Long) -> Unit = { _, _, _ -> }): String = withContext(Dispatchers.IO) {
        if (mode == SilentPacketMode.OFF) return@withContext "off"
        val started = clock()
        var stage = SilentPacketEngineStage.GROUP_CHECK
        fun trace(next: SilentPacketEngineStage, event: SilentPacketEngineEvent) {
            stage = next
            try { onTrace(next, event, (clock() - started).coerceAtLeast(0)) } catch (_: Exception) { }
        }
        fun abort(event: SilentPacketEngineEvent, outcome: String = "unknown"): Nothing {
            trace(stage, event); throw Abort(outcome)
        }
        suspend fun guard() {
            currentCoroutineContext().ensureActive()
            if (!allowed()) abort(SilentPacketEngineEvent.PROTECTED, "protected")
        }
        trace(SilentPacketEngineStage.PREPARE, SilentPacketEngineEvent.STARTED)
        val result = withTimeoutOrNull(PACKET_TIMEOUT + 5_000) {
            try {
                guard()
                if (!request.isValidAt(wallClock())) return@withTimeoutOrNull "stale_request"
                if (request.targetUserId < 0) abort(SilentPacketEngineEvent.FRAME_INVALID)
                var phase = Phase.CHAT
                var deadline = started + PACKET_TIMEOUT
                var lastId = 0L
                var lastCapturedAt = Long.MIN_VALUE
                var surface: NativePacketTicket? = null
                var observations = 0
                var committed = false
                var opened = false
                var verified = request.candidate.confirmedGroup
                var anchor: NativePacketSnapshot? = null
                var returnedFromDetails = false
                var lastWait: Pair<SilentPacketEngineStage, SilentPacketEngineEvent>? = null
                fun fresh(value: NativePacketSnapshot) = clock() - value.ticket.capturedAt in 0..1_000
                // First read is a bounded comparison baseline; only the fresh second ticket is actionable.
                fun baseline(value: NativePacketSnapshot) = clock() - value.ticket.capturedAt in 0..4_000
                fun sameSurface(first: NativePacketSnapshot, second: NativePacketSnapshot) =
                    first.windowId == second.windowId && first.ticket.displayId == second.ticket.displayId &&
                        first.ticket.userId == second.ticket.userId && first.ticket.width == second.ticket.width &&
                        first.ticket.height == second.ticket.height && first.ticket.packetUi == second.ticket.packetUi
                fun validRect(rect: IntRect, ticket: NativePacketTicket) = rect.left >= 0 && rect.top >= 0 &&
                    rect.right <= ticket.width && rect.bottom <= ticket.height && rect.right > rect.left && rect.bottom > rect.top
                fun canAct() = clock() - started in 0 until PACKET_TIMEOUT
                suspend fun observe(): NativePacketSnapshot {
                    val waiting = clock()
                    while (true) {
                        guard()
                        if (clock() < started || clock() >= deadline) abort(SilentPacketEngineEvent.TIMEOUT)
                        trace(SilentPacketEngineStage.CAPTURE, SilentPacketEngineEvent.STARTED)
                        val value = backend.observe()
                        guard()
                        if (clock() >= deadline) abort(SilentPacketEngineEvent.TIMEOUT)
                        if (value == null) {
                            if (clock() - waiting >= 1_000) abort(SilentPacketEngineEvent.NODES_UNAVAILABLE, "nodes_unavailable")
                            pause(100); continue
                        }
                        val ticket = value.ticket
                        if (++observations > 48) abort(SilentPacketEngineEvent.FRAME_LIMIT)
                        if (ticket.frameId <= lastId || ticket.capturedAt < lastCapturedAt ||
                            ticket.displayId <= 0 || ticket.userId != request.targetUserId || value.windowId < 0 ||
                            ticket.width !in 1..4096 || ticket.height !in 1..8192 ||
                            ticket.width.toLong() * ticket.height > 8_388_608)
                            abort(SilentPacketEngineEvent.FRAME_INVALID)
                        val original = surface
                        if (original != null && (ticket.displayId != original.displayId || ticket.userId != original.userId ||
                                ticket.width != original.width || ticket.height != original.height))
                            abort(SilentPacketEngineEvent.FRAME_INVALID)
                        if (!fresh(value)) abort(SilentPacketEngineEvent.FRAME_STALE)
                        surface = ticket
                        lastId = ticket.frameId
                        lastCapturedAt = ticket.capturedAt
                        trace(SilentPacketEngineStage.CAPTURE, SilentPacketEngineEvent.COMPLETED)
                        return value
                    }
                }
                fun target(first: NativePacketSnapshot, second: NativePacketSnapshot, id: String): IntRect {
                    val rect = first.targets[id]
                    if (!sameSurface(first, second) || first.page != second.page || !baseline(first) || !fresh(second) ||
                        rect == null || !validRect(rect, second.ticket) || second.targets[id] != rect ||
                        first.signatures[id].isNullOrBlank() || first.signatures[id] != second.signatures[id])
                        abort(SilentPacketEngineEvent.TARGET_UNCONFIRMED)
                    return rect
                }
                fun eligibleInfo(value: NativePacketSnapshot): Boolean {
                    val page = value.page
                    val id = page.chatInfoButton ?: return false
                    return !page.chatName.isNullOrBlank() && page.chatName !in setOf("微信", "WeChat") &&
                        page.uniqueChatToolbar && !page.packetPanel && page.result == null && !page.verifiedGroupDetails &&
                        page.groupDetailsName == null && page.groupDetailsMemberCount == null &&
                        value.targets[id]?.let { validRect(it, value.ticket) } == true && !value.signatures[id].isNullOrBlank()
                }
                fun restores(value: NativePacketSnapshot): Boolean {
                    val original = anchor ?: return false
                    val id = original.page.chatInfoButton ?: return false
                    return eligibleInfo(value) && sameSurface(original, value) && value.page.chatName == original.page.chatName &&
                        value.page.chatInfoButton == id && value.targets[id] == original.targets[id] &&
                        value.signatures[id] == original.signatures[id]
                }
                suspend fun tap(first: NativePacketSnapshot, id: String, clickStage: SilentPacketEngineStage,
                    reserve: Boolean = false): NativePacketSnapshot {
                    trace(clickStage, SilentPacketEngineEvent.STARTED)
                    pause(350)
                    val second = observe()
                    stage = clickStage
                    val rect = target(first, second, id)
                    if (returnedFromDetails && reserve && (!restores(first) || !restores(second)))
                        abort(SilentPacketEngineEvent.GROUP_UNVERIFIED)
                    guard()
                    if (!canAct()) abort(SilentPacketEngineEvent.ACTION_EXPIRED)
                    if (reserve) {
                        if (committed) abort(SilentPacketEngineEvent.FLOW_STOPPED)
                        committed = true
                        if (!beforeCard()) throw Abort("duplicate")
                        guard()
                        if (!canAct() || !fresh(second)) abort(SilentPacketEngineEvent.ACTION_EXPIRED)
                    }
                    if (!backend.tap((rect.left + rect.right) / 2, (rect.top + rect.bottom) / 2, second.ticket.frameId))
                        abort(SilentPacketEngineEvent.ACTION_FAILED)
                    trace(clickStage, SilentPacketEngineEvent.COMPLETED)
                    return second
                }
                var current = observe()
                while (true) {
                    guard()
                    if (clock() >= deadline) abort(SilentPacketEngineEvent.TIMEOUT)
                    val page = current.page
                    stage = when (phase) {
                        Phase.CHAT, Phase.DETAILS -> SilentPacketEngineStage.GROUP_CHECK
                        Phase.OPEN -> SilentPacketEngineStage.OPEN_CLICK
                        Phase.RESULT -> SilentPacketEngineStage.RESULT_CHECK
                    }
                    if (mode == SilentPacketMode.PROBE) {
                        pause(350)
                        val second = observe()
                        stage = SilentPacketEngineStage.GROUP_CHECK
                        if (!sameSurface(current, second) || current.page != second.page || !baseline(current) || !fresh(second))
                            abort(SilentPacketEngineEvent.TARGET_UNCONFIRMED)
                        if (page.chatName != null && page.chatName != request.candidate.chatName)
                            abort(SilentPacketEngineEvent.GROUP_MISMATCH, "mismatch")
                        return@withTimeoutOrNull if (page.chatName == request.candidate.chatName &&
                            request.candidate.confirmedGroup && page.cards.isNotEmpty()) "probe_candidate" else "probe_no_candidate"
                    }
                    when (phase) {
                        Phase.CHAT -> {
                            if (page.chatName != null) {
                                val matching = page.chatName == request.candidate.chatName
                                if (!verified || (!matching && !returnedFromDetails)) {
                                    if (eligibleInfo(current)) {
                                        anchor = current
                                        tap(current, page.chatInfoButton!!, SilentPacketEngineStage.INFO_CLICK)
                                        phase = Phase.DETAILS
                                    } else if (!matching) abort(SilentPacketEngineEvent.GROUP_MISMATCH, "mismatch")
                                } else if (page.cards.isNotEmpty()) {
                                    if (returnedFromDetails && !restores(current)) abort(SilentPacketEngineEvent.GROUP_UNVERIFIED)
                                    tap(current, page.cards.last(), SilentPacketEngineStage.CARD_CLICK, reserve = true)
                                    phase = Phase.OPEN
                                }
                            }
                        }
                        Phase.DETAILS -> if (page.verifiedGroupDetails) {
                            if (page.groupDetailsName != request.candidate.chatName || (page.groupDetailsMemberCount ?: 0) < 2)
                                abort(SilentPacketEngineEvent.GROUP_UNVERIFIED)
                            pause(350)
                            val second = observe()
                            stage = SilentPacketEngineStage.GROUP_BACK
                            if (!sameSurface(current, second) || !baseline(current) || !fresh(second) || current.page != second.page ||
                                !second.page.verifiedGroupDetails || second.page.groupDetailsName != request.candidate.chatName ||
                                second.page.groupDetailsMemberCount != page.groupDetailsMemberCount || page.packetPanel)
                                abort(SilentPacketEngineEvent.GROUP_UNVERIFIED)
                            guard()
                            if (!canAct()) abort(SilentPacketEngineEvent.ACTION_EXPIRED)
                            if (!backend.back()) abort(SilentPacketEngineEvent.ACTION_FAILED)
                            trace(SilentPacketEngineStage.GROUP_BACK, SilentPacketEngineEvent.COMPLETED)
                            pause(350)
                            val restoredFirst = observe()
                            pause(350)
                            val restoredSecond = observe()
                            stage = SilentPacketEngineStage.GROUP_BACK
                            if (!baseline(restoredFirst) || !fresh(restoredSecond) || !restores(restoredFirst) || !restores(restoredSecond))
                                abort(SilentPacketEngineEvent.GROUP_UNVERIFIED)
                            verified = true
                            returnedFromDetails = true
                            phase = Phase.CHAT
                            current = restoredSecond
                            continue
                        }
                        Phase.OPEN, Phase.RESULT -> {
                            if (page.chatName != null && page.chatName != (anchor?.page?.chatName ?: request.candidate.chatName))
                                abort(SilentPacketEngineEvent.GROUP_MISMATCH)
                            if (page.packetPanel && page.result != null) {
                                val resultSurface = when (page.result) {
                                    "已领取" -> current.ticket.packetUi == NativePacketUi.DETAIL
                                    "已过期", "已被领完" -> current.ticket.packetUi == NativePacketUi.RECEIVE ||
                                        current.ticket.packetUi == NativePacketUi.DETAIL
                                    else -> false
                                }
                                if (!resultSurface) abort(SilentPacketEngineEvent.RESULT_UNCONFIRMED)
                                pause(350)
                                val second = observe()
                                stage = SilentPacketEngineStage.RESULT_CHECK
                                if (!sameSurface(current, second) || !baseline(current) || !fresh(second) ||
                                    !second.page.packetPanel || current.page != second.page || page.result != second.page.result)
                                    abort(SilentPacketEngineEvent.RESULT_UNCONFIRMED)
                                trace(SilentPacketEngineStage.RESULT_CHECK, SilentPacketEngineEvent.COMPLETED)
                                return@withTimeoutOrNull when (page.result) {
                                    "已领取" -> "claimed"
                                    "已过期" -> "expired"
                                    "已被领完" -> "exhausted"
                                    else -> "unknown"
                                }
                            }
                            if (phase == Phase.OPEN && page.packetPanel && page.openButton != null) {
                                if (current.ticket.packetUi != NativePacketUi.RECEIVE)
                                    abort(SilentPacketEngineEvent.TARGET_UNCONFIRMED)
                                if (opened) abort(SilentPacketEngineEvent.FLOW_STOPPED)
                                opened = true
                                deadline = maxOf(deadline, clock() + 5_000)
                                tap(current, page.openButton, SilentPacketEngineStage.OPEN_CLICK)
                                phase = Phase.RESULT
                            }
                        }
                    }
                    val wait = stage to when (phase) {
                        Phase.CHAT -> if (page.chatName == null) SilentPacketEngineEvent.WAIT_CHAT_TITLE else
                            if (!verified) SilentPacketEngineEvent.WAIT_INFO_TARGET else SilentPacketEngineEvent.WAIT_CARD
                        Phase.DETAILS -> SilentPacketEngineEvent.WAIT_GROUP_DETAILS
                        Phase.OPEN -> SilentPacketEngineEvent.WAIT_OPEN_PANEL
                        Phase.RESULT -> SilentPacketEngineEvent.WAIT_RESULT
                    }
                    if (wait != lastWait) { trace(wait.first, wait.second); lastWait = wait }
                    pause(350)
                    current = observe()
                }
                @Suppress("UNREACHABLE_CODE") "unknown"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (stopped: Abort) { stopped.outcome }
            catch (_: Exception) { trace(stage, SilentPacketEngineEvent.FAILED); "unknown" }
        }
        if (result == null) trace(stage, SilentPacketEngineEvent.TIMEOUT)
        result ?: "unknown"
    }
}
