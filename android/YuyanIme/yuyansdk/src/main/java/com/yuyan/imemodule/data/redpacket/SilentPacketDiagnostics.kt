package com.yuyan.imemodule.data.redpacket

internal enum class SilentPacketDiagnosticChannel { NOTICE, TASK, RUNTIME }

/** Fixed vocabulary: no notification text, group names, image data, exception text or intent IDs. */
internal enum class SilentPacketDiagnosticEvent(val channel: SilentPacketDiagnosticChannel) {
    NOTICE_SEEN(SilentPacketDiagnosticChannel.NOTICE),
    MODE_OFF(SilentPacketDiagnosticChannel.NOTICE),
    PARSE_REJECTED(SilentPacketDiagnosticChannel.NOTICE),
    PARSED(SilentPacketDiagnosticChannel.NOTICE),
    PROFILE_REJECTED(SilentPacketDiagnosticChannel.NOTICE),
    PROFILE_MATCHED(SilentPacketDiagnosticChannel.NOTICE),
    GROUP_REJECTED(SilentPacketDiagnosticChannel.NOTICE),
    ELIGIBLE(SilentPacketDiagnosticChannel.NOTICE),
    DEDUPLICATED(SilentPacketDiagnosticChannel.NOTICE),
    QUEUED(SilentPacketDiagnosticChannel.NOTICE),
    QUEUE_DROPPED(SilentPacketDiagnosticChannel.NOTICE),
    TASK_STARTED(SilentPacketDiagnosticChannel.TASK),
    TASK_BLOCKED(SilentPacketDiagnosticChannel.TASK),
    TASK_DUPLICATE(SilentPacketDiagnosticChannel.TASK),
    TASK_STALE(SilentPacketDiagnosticChannel.TASK),
    TASK_GROUP_REJECTED(SilentPacketDiagnosticChannel.TASK),
    TASK_START_FAILED(SilentPacketDiagnosticChannel.TASK),
    TASK_LAUNCH_FAILED(SilentPacketDiagnosticChannel.TASK),
    TASK_CANCELLED(SilentPacketDiagnosticChannel.TASK),
    TASK_EXCEPTION(SilentPacketDiagnosticChannel.TASK),
    TASK_CLAIMED(SilentPacketDiagnosticChannel.TASK),
    TASK_EXPIRED(SilentPacketDiagnosticChannel.TASK),
    TASK_EXHAUSTED(SilentPacketDiagnosticChannel.TASK),
    TASK_PROBE_CANDIDATE(SilentPacketDiagnosticChannel.TASK),
    TASK_PROBE_NO_CANDIDATE(SilentPacketDiagnosticChannel.TASK),
    TASK_MISMATCH(SilentPacketDiagnosticChannel.TASK),
    TASK_UNKNOWN(SilentPacketDiagnosticChannel.TASK),
    TASK_OFF(SilentPacketDiagnosticChannel.TASK),
    TASK_PROTECTED(SilentPacketDiagnosticChannel.TASK),
    FOREGROUND_PROTECTED(SilentPacketDiagnosticChannel.RUNTIME),
    FOREGROUND_READY(SilentPacketDiagnosticChannel.RUNTIME),
    INPUT_STOPPED(SilentPacketDiagnosticChannel.RUNTIME),
    INPUT_STOPPED_TOUCH(SilentPacketDiagnosticChannel.RUNTIME),
    INPUT_STOPPED_TEXT(SilentPacketDiagnosticChannel.RUNTIME),
    INPUT_STOPPED_CLICK(SilentPacketDiagnosticChannel.RUNTIME),
    INPUT_STOPPED_SCROLL(SilentPacketDiagnosticChannel.RUNTIME),
    TASK_START_INVALIDATED(SilentPacketDiagnosticChannel.TASK),
    TASK_LAUNCH_INVALIDATED(SilentPacketDiagnosticChannel.TASK),
    BACKEND_START_REPORTED(SilentPacketDiagnosticChannel.TASK),
    USER_STOPPED(SilentPacketDiagnosticChannel.RUNTIME),
    CONFIG_CHANGED(SilentPacketDiagnosticChannel.RUNTIME),
    SERVICE_CONNECTED(SilentPacketDiagnosticChannel.RUNTIME),
    SERVICE_DISCONNECTED(SilentPacketDiagnosticChannel.RUNTIME),
    SHIZUKU_DISCONNECTED(SilentPacketDiagnosticChannel.RUNTIME),
    WAITING_INPUT(SilentPacketDiagnosticChannel.RUNTIME),
    WAITING_CONNECTION(SilentPacketDiagnosticChannel.RUNTIME),
    WAITING_PROTECTION(SilentPacketDiagnosticChannel.RUNTIME),
    WAITING_PERMISSIONS(SilentPacketDiagnosticChannel.RUNTIME),
    DIAGNOSTIC_STARTED(SilentPacketDiagnosticChannel.RUNTIME),
    DIAGNOSTIC_DONE(SilentPacketDiagnosticChannel.RUNTIME),
    DIAGNOSTIC_FAILED(SilentPacketDiagnosticChannel.RUNTIME),
}

/** Whitelisted backend states; original response strings are never retained. */
internal enum class SilentPacketBackendState {
    NOT_REPORTED, UNKNOWN, IDLE, RUNNING, SHELL_REQUIRED, INVALID_USER, USER_UNAVAILABLE,
    MAIN_WECHAT_IN_USE, MAIN_FOCUS_UNKNOWN, INVALID_DISPLAY, MAIN_FOCUS_CHANGED,
    SECONDARY_UNVERIFIED, START_FAILED, USER_CHANGED, EXPIRED, MAIN_FOCUS_LOST,
    STATUS_UNKNOWN, STOPPED, DESTROYED, SERVICE_UNRESPONSIVE,
}

internal data class SilentPacketDiagnosticRecord(
    val event: SilentPacketDiagnosticEvent,
    val sequence: Long,
    val elapsedMs: Long,
    val backendState: SilentPacketBackendState? = null,
)

internal data class SilentPacketEngineRecord(
    val stage: SilentPacketEngineStage,
    val event: SilentPacketEngineEvent,
    val elapsedMs: Long,
)

internal data class SilentPacketDiagnosticSnapshot(
    val sequence: Long,
    val counters: Map<SilentPacketDiagnosticEvent, Long>,
    val lastNotice: SilentPacketDiagnosticEvent?,
    val lastTask: SilentPacketDiagnosticEvent?,
    val lastRuntime: SilentPacketDiagnosticEvent?,
    val selectedUser: Int,
    val noticeUser: Int,
    val sbnUser: Int,
    val intentUser: Int,
    val lastBackendState: SilentPacketBackendState,
    val history: List<SilentPacketDiagnosticRecord>,
    val engineHistory: List<SilentPacketEngineRecord> = emptyList(),
) {
    fun render(): String = buildString {
        append("诊断（本进程内存，重启清零）：selectedUser=").append(selectedUser)
        append(" noticeUser=").append(noticeUser)
        append(" sbnUser=").append(sbnUser).append(" intentUser=").append(intentUser)
        append(" sequence=").append(sequence)
        append(" backend=").append(lastBackendState.name)
        append("\n最近通知=").append(lastNotice?.name ?: "NONE")
        append(" 最近任务=").append(lastTask?.name ?: "NONE")
        append(" 运行边界=").append(lastRuntime?.name ?: "NONE")
        append("\n")
        // Always show arrival count: zero distinguishes no delivered notification from rejected parsing.
        append("NOTICE_SEEN=").append(counters[SilentPacketDiagnosticEvent.NOTICE_SEEN] ?: 0)
        counters.filter { (event, count) -> event != SilentPacketDiagnosticEvent.NOTICE_SEEN && count > 0 }
            .forEach { (event, count) -> append(" ").append(event.name).append("=").append(count) }
        append("\n事件顺序（末16条，单调毫秒）：")
        history.takeLast(16).forEach { entry ->
            append("\n#").append(entry.sequence).append("@").append(entry.elapsedMs)
            append(" ").append(entry.event.name)
            entry.backendState?.let { append("[").append(it.name).append("]") }
        }
        append("\n引擎阶段（末16条，任务耗时）：")
        engineHistory.takeLast(16).forEach { entry ->
            append("\n").append(entry.stage.name).append(" ").append(entry.event.name)
            append(" @").append(entry.elapsedMs).append("ms")
        }
    }
}

/** Thread-safe metadata only. Records observations; never schedules, retains or replays a packet. */
internal class SilentPacketDiagnostics(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val engineHistory = ArrayDeque<SilentPacketEngineRecord>()

    @Synchronized fun engine(stage: SilentPacketEngineStage, event: SilentPacketEngineEvent, elapsedMs: Long) {
        engineHistory.addLast(SilentPacketEngineRecord(stage, event, elapsedMs.coerceAtLeast(0)))
        if (engineHistory.size > 32) engineHistory.removeFirst()
    }
    private val counts = LongArray(SilentPacketDiagnosticEvent.entries.size)
    private var sequence = 0L
    private var lastNotice: SilentPacketDiagnosticEvent? = null
    private var lastTask: SilentPacketDiagnosticEvent? = null
    private var lastRuntime: SilentPacketDiagnosticEvent? = null
    private var selectedUser = -1
    private var noticeUser = -1
    private var sbnUser = -1
    private var intentUser = -1
    private var lastBackendState = SilentPacketBackendState.NOT_REPORTED
    private var elapsedMs = Long.MIN_VALUE
    private val history = ArrayDeque<SilentPacketDiagnosticRecord>()

    @Synchronized fun record(event: SilentPacketDiagnosticEvent, selectedUser: Int? = null, noticeUser: Int? = null,
                             sbnUser: Int? = null, intentUser: Int? = null) {
        counts[event.ordinal] = increment(counts[event.ordinal])
        sequence = increment(sequence)
        elapsedMs = maxOf(elapsedMs, clock())
        history.addLast(SilentPacketDiagnosticRecord(event, sequence, elapsedMs,
            lastBackendState.takeIf { event == SilentPacketDiagnosticEvent.BACKEND_START_REPORTED }))
        if (history.size > 32) history.removeFirst()
        selectedUser?.let { this.selectedUser = it.takeIf { id -> id >= -1 } ?: -1 }
        noticeUser?.let { this.noticeUser = it.takeIf { id -> id >= -1 } ?: -1 }
        // Distinct observations for OEM bridges; these fields never grant profile authorization.
        sbnUser?.let { this.sbnUser = it.takeIf { id -> id >= -1 } ?: -1 }
        intentUser?.let { this.intentUser = it.takeIf { id -> id >= -1 } ?: -1 }
        when (event.channel) {
            SilentPacketDiagnosticChannel.NOTICE -> lastNotice = event
            SilentPacketDiagnosticChannel.TASK -> lastTask = event
            SilentPacketDiagnosticChannel.RUNTIME -> lastRuntime = event
        }
    }

    @Synchronized fun startedBackend(state: String) {
        lastBackendState = SilentPacketBackendState.entries.firstOrNull {
            it != SilentPacketBackendState.NOT_REPORTED && it.name == state
        } ?: SilentPacketBackendState.UNKNOWN
        record(SilentPacketDiagnosticEvent.BACKEND_START_REPORTED)
    }

    fun finished(result: String) = record(when (result) {
        "claimed" -> SilentPacketDiagnosticEvent.TASK_CLAIMED
        "expired" -> SilentPacketDiagnosticEvent.TASK_EXPIRED
        "exhausted" -> SilentPacketDiagnosticEvent.TASK_EXHAUSTED
        "probe_candidate" -> SilentPacketDiagnosticEvent.TASK_PROBE_CANDIDATE
        "probe_no_candidate" -> SilentPacketDiagnosticEvent.TASK_PROBE_NO_CANDIDATE
        "mismatch" -> SilentPacketDiagnosticEvent.TASK_MISMATCH
        "stale_request" -> SilentPacketDiagnosticEvent.TASK_STALE
        "duplicate" -> SilentPacketDiagnosticEvent.TASK_DUPLICATE
        "off" -> SilentPacketDiagnosticEvent.TASK_OFF
        "protected" -> SilentPacketDiagnosticEvent.TASK_PROTECTED
        else -> SilentPacketDiagnosticEvent.TASK_UNKNOWN
    })

    @Synchronized fun snapshot(): SilentPacketDiagnosticSnapshot = SilentPacketDiagnosticSnapshot(
        sequence, SilentPacketDiagnosticEvent.entries.associateWith { counts[it.ordinal] },
        lastNotice, lastTask, lastRuntime, selectedUser, noticeUser, sbnUser, intentUser,
        lastBackendState, history.toList(), engineHistory.toList(),
    )

    private fun increment(value: Long): Long = if (value == Long.MAX_VALUE) value else value + 1
}
