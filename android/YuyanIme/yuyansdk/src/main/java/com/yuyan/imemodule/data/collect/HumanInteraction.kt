package com.yuyan.imemodule.data.collect

internal data class HumanInteraction(val at: Long, val source: String)

internal fun humanInteractionAt(eventUptime: Long, source: String, wallNow: Long, uptimeNow: Long): HumanInteraction? {
    if (eventUptime <= 0 || eventUptime > uptimeNow || source !in setOf("touch", "key", "ime_input")) return null
    val at = wallNow - (uptimeNow - eventUptime)
    return if (at > 0) HumanInteraction(at, source) else null
}

/** Conservatively excludes our synthetic gesture, including its delayed accessibility receipt. */
internal class HumanInteractionAutomationGuard {
    private val windows = java.util.ArrayDeque<LongRange>()
    @Synchronized fun suppress(at: Long, duration: Long) {
        windows.addLast(at..at + duration)
        while (windows.size > 128) windows.removeFirst()
    }
    @Synchronized fun suppressed(eventUptime: Long) = windows.any { eventUptime in it }
    @Synchronized fun ranges(): List<LongRange> = windows.toList()
    @Synchronized fun retain(from: Long, until: Long) { windows.removeAll { it.first < from || it.first > until } }
}

/** Recreated from the private preference after process restart; no input-thread disk access. */
internal class HumanInteractionAutomationHistory(saved: String, now: Long) {
    private val guard = HumanInteractionAutomationGuard()
    init {
        if (saved.length <= 8_192) saved.split(';').takeLast(128).forEach { row ->
            val fields = row.split(':')
            val start = fields.getOrNull(0)?.toLongOrNull()
            val end = fields.getOrNull(1)?.toLongOrNull()
            if (start != null && end != null && start in (now - 86_400_000L)..now && end - start in 0L..1_000L)
                guard.suppress(start, end - start)
        }
    }
    @Synchronized fun record(now: Long): String {
        guard.retain(now - 86_400_000L, now)
        guard.suppress(now, 1_000L)
        return guard.ranges().joinToString(";") { "${it.first}:${it.last}" }
    }
    fun suppressed(eventWallTime: Long) = guard.suppressed(eventWallTime)
}
