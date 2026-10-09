package com.yuyan.imemodule.data.redpacket

import android.view.accessibility.AccessibilityEvent

internal fun silentPacketUserAction(eventType: Int): Boolean =
    eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START ||
        eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
        eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
        eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED

internal fun silentPacketEligible(mode: String, selectedUser: Int, noticeUser: Int,
    groups: Set<String>, packet: PacketCandidate): Boolean =
    mode in setOf("AUTO", "PROBE") && selectedUser >= 0 && selectedUser == noticeUser &&
        packet.chatName in groups

/** 点击红包卡片前落盘，结果未知也不自动重放；只保存散列ID与时间。 */
internal class SilentPacketLedger(saved: String, private val save: (String) -> Boolean) {
    private val entries = linkedMapOf<String, Long>()
    init {
        saved.lineSequence().forEach { row ->
            val parts = row.split(':')
            if (parts.size == 2 && parts[0].matches(Regex("[a-f0-9]{64}")))
                parts[1].toLongOrNull()?.takeIf { it >= 0 }?.let { entries[parts[0]] = it }
        }
    }
    fun contains(id: String): Boolean = id in entries
    fun reserve(id: String, now: Long): Boolean {
        if (!id.matches(Regex("[a-f0-9]{64}")) || now < 0 || entries.values.any { it > now }) return false
        entries.entries.removeAll { now - it.value > 24 * 60 * 60_000L }
        if (id in entries) return false
        val next = LinkedHashMap(entries)
        next[id] = now
        while (next.size > 128) next.remove(next.keys.first())
        if (!save(next.entries.joinToString("\n") { "${it.key}:${it.value}" })) return false
        entries.clear(); entries.putAll(next)
        return true
    }
}
