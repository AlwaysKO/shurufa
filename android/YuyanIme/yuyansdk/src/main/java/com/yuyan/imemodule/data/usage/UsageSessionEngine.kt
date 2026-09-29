package com.yuyan.imemodule.data.usage

import java.util.UUID

internal data class UsageEvent(val time: Long, val type: String, val packageName: String? = null, val token: String = "")
internal data class UsageActive(val packageName: String, val token: String, val start: Long, val pausedAt: Long? = null)
internal data class UsageState(
    val cursor: Long, val active: UsageActive? = null, val blocked: Boolean = false,
    val suspended: Boolean = false, val locked: Boolean = false, val boot: Int = -1, val elapsed: Long = 0, val observedAt: Long = 0, val candidate: UsageActive? = null,
)
internal data class UsageRecord(
    val id: String, val kind: String, val packageName: String?, val appName: String?,
    val startMs: Long, val endMs: Long, val endReason: String,
)
internal data class UsageReduction(val state: UsageState, val records: List<UsageRecord>)

/** Pure reducer. A cursor is an exclusive query end, never a fabricated session end. */
internal object UsageSessionEngine {
    // 保留自身前台事件作为切换边界，只禁止生成自身使用段，不能延长前一个 App 的时长。
    private val ownPackages = setOf(
        "com.yuyan.pinyin", "com.yuyan.pinyin.debug", "com.yuyan.pinyin.release",
        "com.yuyan.pinyin.offline", "com.yuyan.pinyin.offline.debug", "com.yuyan.pinyin.offline.release",
    )
    private const val ACTIVITY_TRANSITION_MS = 1000L
    private fun record(kind: String, pkg: String?, start: Long, end: Long, reason: String) = UsageRecord(
        UUID.nameUUIDFromBytes("$kind|$pkg|$start|$end|$reason".toByteArray(Charsets.UTF_8)).toString(),
        kind, pkg, null, start, end, reason,
    )
    fun gap(state: UsageState, until: Long, reason: String): UsageReduction {
        val start = state.active?.start ?: state.cursor
        val records = if (until > start) listOf(record("gap", null, start, until, reason)) else emptyList()
        return UsageReduction(state.copy(cursor=until, active=null, blocked=false, locked=false, candidate=null), records)
    }
    fun reduce(previous: UsageState, events: List<UsageEvent>, until: Long): UsageReduction {
        require(until >= previous.cursor)
        var active=previous.active
        var blocked=previous.blocked
        var locked=previous.locked
        var candidate=previous.candidate
        val records=mutableListOf<UsageRecord>()
        fun close(time: Long, reason: String) {
            active?.let { a ->
                val end=a.pausedAt?.coerceAtMost(time) ?: time
                if (end>a.start && a.packageName !in ownPackages) records.add(record("usage",a.packageName,a.start,end,reason))
            }
            active=null
        }
        for (e in events.filter { it.time >= previous.cursor && it.time < until }.sortedBy { it.time }) {
            val paused=active?.pausedAt
            if (paused!=null && e.time-paused>ACTIVITY_TRANSITION_MS) close(paused,"pause")
            when(e.type) {
                "resume" -> if (!e.packageName.isNullOrBlank()) {
                    if (blocked) candidate=UsageActive(e.packageName,e.token,e.time)
                    else if (active?.packageName==e.packageName) active=active!!.copy(token=e.token,pausedAt=null)
                    else { close(e.time,"switch"); active=UsageActive(e.packageName,e.token,e.time) }
                }
                // STOP can arrive for an older instance of the same Activity class after RESUME.
                // Public UsageEvents exposes className, not instanceId; only PAUSE ends foreground.
                "pause" -> {
                    if (candidate?.packageName==e.packageName && candidate?.token==e.token) candidate=null
                    if (active?.packageName==e.packageName && active?.token==e.token && active?.pausedAt==null) active=active!!.copy(pausedAt=e.time)
                }
                "off" -> { close(e.time,e.type); blocked=true }
                "lock" -> { close(e.time,e.type); blocked=true; locked=true }
                "on" -> if (!locked) {
                    blocked=false
                    candidate?.let { active=it.copy(start=e.time,pausedAt=null) }; candidate=null
                }
                "unlock" -> {
                    blocked=false; locked=false
                    candidate?.let { active=it.copy(start=e.time,pausedAt=null) }; candidate=null
                }
                "startup", "shutdown" -> {
                    active?.let { if(e.time>it.start) records.add(record("gap",null,it.start,e.time,e.type)) }
                    active=null; blocked=false; locked=false; candidate=null
                }
            }
        }
        active?.pausedAt?.let { if (until-it>ACTIVITY_TRANSITION_MS) close(it,"pause") }
        return UsageReduction(previous.copy(cursor=until,active=active,blocked=blocked,locked=locked,candidate=candidate),records)
    }
}

/** Without a trustworthy boot counter, a new process conservatively abandons open history. */
internal fun usageDiscontinuity(state: UsageState, now: Long, elapsed: Long, boot: Int, firstPoll: Boolean): String? = when {
    firstPoll && (boot < 0 || state.boot < 0) -> "process_restart"
    state.elapsed > 0 && (elapsed < state.elapsed || (boot >= 0 && state.boot >= 0 && boot != state.boot)) -> "reboot"
    now < state.cursor || (state.elapsed > 0 && kotlin.math.abs((now-(state.observedAt.takeIf { it > 0 } ?: state.cursor))-(elapsed-state.elapsed)) > 5000) -> "clock_changed"
    else -> null
}
