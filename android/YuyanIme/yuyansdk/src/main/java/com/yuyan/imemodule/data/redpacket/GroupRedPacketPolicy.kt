package com.yuyan.imemodule.data.redpacket

import java.security.MessageDigest

internal const val PACKET_WECHAT = "com.tencent.mm"
internal const val PACKET_TIMEOUT = 12_000L
internal const val PACKET_NOTICE_MAX_AGE = 15_000L

internal data class PacketNotice(
    val packageName: String, val key: String, val title: String, val text: String,
    val postedAt: Long, val group: Boolean?,
)
internal data class PacketCandidate(val id: String, val chatName: String, val confirmedGroup: Boolean)

internal fun groupPacketCandidate(notice: PacketNotice, now: Long): PacketCandidate? {
    if (notice.packageName != PACKET_WECHAT || notice.group == false ||
        now - notice.postedAt !in 0..PACKET_NOTICE_MAX_AGE) return null
    val title = notice.title.trim()
    if (title.isBlank() || title in setOf("微信", "WeChat") || title.contains('…') || title.endsWith("...")) return null
    val text = notice.text.trim().replace(Regex("^\\[\\d+条]\\s*"), "")
    val marker = Regex("^\\[(?:微信)?红包]")
    val senderBody = Regex("^.{1,80}?[：:]\\s*(\\[(?:微信)?红包].*)$").matchEntire(text)?.groupValues?.get(1)
    if (!(notice.group == true && marker.containsMatchIn(text)) &&
        (senderBody == null || !marker.containsMatchIn(senderBody))) return null
    val identity = listOf(notice.key, notice.postedAt.toString(), title, text).joinToString("\u0000")
    val id = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
    return PacketCandidate(id, title, notice.group == true)
}

internal fun isAvailablePacketCard(labels: List<String>, incoming: Boolean): Boolean =
    incoming && labels.any { it == "微信红包" } && labels.none { label ->
        listOf("已领取", "已被领完", "已过期", "已退还", "已领完", "专属红包").any(label::contains)
    }

internal data class PacketPage(
    val chatName: String? = null, val groupChat: Boolean = false, val cards: List<String> = emptyList(),
    val packetPanel: Boolean = false, val openButton: String? = null, val result: String? = null,
    val chatInfoButton: String? = null, val verifiedGroupDetails: Boolean = false,
)
internal sealed interface PacketAction {
    data object Wait : PacketAction
    data object Stop : PacketAction
    data object Back : PacketAction
    data class Click(val id: String) : PacketAction
    data class Finish(val result: String) : PacketAction
}

/** 先确认群资料，再返回原会话；一次任务最多点一次卡片和一次拆开。 */
internal class PacketFlow(val candidate: PacketCandidate, private val startedAt: Long) {
    private var stage = 0
    private var resultDeadline = startedAt + PACKET_TIMEOUT
    fun canClick(now: Long): Boolean = stage != 5 && now - startedAt in 0 until PACKET_TIMEOUT
    fun step(page: PacketPage, now: Long): PacketAction {
        if (stage == 5 || now < startedAt || now >= resultDeadline) return PacketAction.Stop
        if (page.chatName != null && page.chatName != candidate.chatName) return PacketAction.Stop
        if (stage == 0) {
            if (page.chatName == null) return PacketAction.Wait
            if (!page.groupChat && !candidate.confirmedGroup) return PacketAction.Stop
            val info = page.chatInfoButton ?: return PacketAction.Wait
            stage = 1
            return PacketAction.Click(info)
        }
        if (stage == 1) {
            if (!page.verifiedGroupDetails) return PacketAction.Wait
            stage = 2
            return PacketAction.Back
        }
        if (stage == 2) {
            if (page.chatName == null) return PacketAction.Wait
            val card = page.cards.lastOrNull() ?: return PacketAction.Wait
            stage = 3
            return PacketAction.Click(card)
        }
        if (!page.packetPanel) return PacketAction.Wait
        page.result?.let { stage = 5; return PacketAction.Finish(it) }
        if (stage == 3 && page.openButton != null) {
            stage = 4
            // 给已发起拆开的结果页最多五秒加载时间；点击仍受原十二秒上限约束。
            resultDeadline = maxOf(resultDeadline, now + 5_000)
            return PacketAction.Click(page.openButton)
        }
        return PacketAction.Wait
    }
}

/** 只记住当前可见的未领取卡片；卡片消失或变成已领取后允许同位置出现的新红包。 */
internal class VisiblePacketAttempts {
    private val chats = linkedMapOf<String, MutableSet<String>>()
    fun observe(chat: String, available: Collection<String>) {
        chats[chat]?.retainAll(available.toSet())
        while (chats.size > 32) chats.remove(chats.keys.first())
    }
    fun accept(chat: String, signature: String): Boolean = chats.getOrPut(chat) { mutableSetOf() }.add(signature)
    fun completed(chat: String, signature: String) { chats[chat]?.remove(signature) }
}

/** 控件没有稳定消息ID时限制相同外观的尝试频率，防止已领取状态未暴露造成循环。 */
internal class PacketRetryBudget {
    private val attempts = linkedMapOf<String, MutableList<Long>>()
    fun allow(signature: String, now: Long): Boolean {
        val times = attempts.getOrPut(signature) { mutableListOf() }
        times.removeAll { now - it >= 15_000 }
        if (times.size >= 3) return false
        times.add(now)
        while (attempts.size > 128) attempts.remove(attempts.keys.first())
        return true
    }
}

internal class PacketClickReceipt {
    private var expected: Pair<Int, String>? = null
    private var deadline = 0L
    fun expect(window: Int, node: String, now: Long) { expected = window to node; deadline = now + 600 }
    fun consume(window: Int, node: String?, now: Long): Boolean {
        val matches = node != null && expected == (window to node) && now <= deadline
        expected = null
        return matches
    }
    fun clear() { expected = null }
}

internal class PacketDeduplicator(private val capacity: Int = 128) {
    private val seen = LinkedHashSet<String>()
    fun accept(id: String): Boolean {
        if (!seen.add(id)) return false
        while (seen.size > capacity) seen.remove(seen.first())
        return true
    }
}
