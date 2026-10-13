package com.yuyan.imemodule.data.redpacket

import com.yuyan.imemodule.data.capture.ui.IntRect

/** Bounded temporary tree. ownerUserId is the shell ticket's display ownership, not a node UID. */
internal data class NativePacketNode(val path: String, val parent: String?, val bounds: IntRect,
    val text: String = "", val description: String = "", val clickable: Boolean = false,
    val enabled: Boolean = true, val visible: Boolean = true, val packageName: String = PACKET_WECHAT,
    val windowId: Int, val className: String = "android.view.View", val editable: Boolean = false)
internal data class NativePacketWindow(val displayId: Int, val ownerUserId: Int, val windowId: Int,
    val bounds: IntRect, val nodes: List<NativePacketNode>, val application: Boolean = true,
    val extraWindows: Boolean = false, val packageName: String = PACKET_WECHAT,
    val blockedBounds: List<IntRect> = emptyList(), val windowCount: Int = 1)
internal fun parseNativePacketPage(ticket: NativePacketTicket, window: NativePacketWindow): NativePacketSnapshot? {
    fun valid(r: IntRect) = r.left >= 0 && r.top >= 0 && r.right <= ticket.width &&
        r.bottom <= ticket.height && r.right > r.left && r.bottom > r.top
    if (ticket.displayId <= 0 || ticket.userId < 0 || ticket.width <= 0 || ticket.height <= 0 ||
        ticket.width > 4096 || ticket.height > 8192 || !nativePacketPixelBudget(ticket.width.toLong() * ticket.height) ||
        window.displayId != ticket.displayId || window.ownerUserId != ticket.userId || window.windowId < 0 ||
        !window.application || window.extraWindows || window.packageName != PACKET_WECHAT ||
        (!valid(window.bounds) || window.blockedBounds.any { !nativePacketSystemEdge(it,ticket) }) || window.nodes.isEmpty() || window.nodes.size > 600) return null
    val all = window.nodes
    if (all.map { it.path }.toSet().size != all.size || all.count { it.parent == null } != 1 ||
        all.firstOrNull { it.parent == null }?.path != "0" ||
        all.sumOf { it.text.length + it.description.length } > 65_536 ||
        all.any { n -> n.packageName != PACKET_WECHAT || n.windowId != window.windowId ||
            n.text.length > 2048 || n.description.length > 2048 || n.className.length > 256 ||
            !Regex("0(?:/[0-9]{1,2}){0,30}").matches(n.path) ||
            (n.visible && !valid(n.bounds)) }) return null
    val byPath = all.associateBy { it.path }
    if (all.any { it.parent != null && (it.parent != it.path.substringBeforeLast('/') ||
            byPath[it.parent] == null) }) return null
    fun blocked(r:IntRect)=window.blockedBounds.any { b->r.left<b.right&&r.right>b.left&&r.top<b.bottom&&r.bottom>b.top }
    val nodes = all.filter { it.visible && !blocked(it.bounds) }
    fun labels(n: NativePacketNode) = listOf(n.text.trim(), n.description.trim()).filter { it.isNotEmpty() }.distinct()
    fun words(n: NativePacketNode) = nodes.filter { it.path == n.path || it.path.startsWith(n.path + "/") }
        .flatMap(::labels).distinct()
    fun cx(r: IntRect) = (r.left + r.right) / 2.0
    fun cy(r: IntRect) = (r.top + r.bottom) / 2.0
    fun ancestor(node: NativePacketNode): NativePacketNode? {
        var n: NativePacketNode? = node
        repeat(6) {
            val current = n ?: return null
            if (current.enabled && current.visible && current.clickable && valid(current.bounds) && !blocked(current.bounds)) return current
            n = current.parent?.let(byPath::get)
        }
        return null
    }
    val targets = linkedMapOf<String, IntRect>()
    val signatures = linkedMapOf<String, String>()
    fun target(key: String, node: NativePacketNode) {
        targets[key] = node.bounds
        val semantic = buildString {
            append(node.path).append('|').append(node.bounds)
            nodes.filter { it.path == node.path || it.path.startsWith(node.path + "/") }.sortedBy { it.path }.forEach {
                append('|').append(it.path).append('|').append(it.className).append('|').append(it.text)
                append('|').append(it.description).append('|').append(it.bounds).append('|').append(it.enabled)
            }
        }
        signatures[key] = java.security.MessageDigest.getInstance("SHA-256").digest(semantic.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
    fun snapshot(page: PacketPage) = NativePacketSnapshot(ticket, window.windowId, page, targets.toMap(), signatures.toMap(),window.blockedBounds.toList())
    fun top(n: NativePacketNode) = cy(n.bounds) in ticket.height * .025..ticket.height * .14 &&
        n.bounds.left >= ticket.width * .10 && n.bounds.right <= ticket.width * .90 &&
        n.bounds.bottom - n.bounds.top <= ticket.height * .065 && n.bounds.right - n.bounds.left >= ticket.width * .055
    val infoPattern = Regex("^(?:聊天信息|群聊信息)[（(]([0-9]{1,5})[）)]$")
    val titleNodes = nodes.filter { it.text.isNotBlank() && top(it) && !it.editable }
    val detailTitles = titleNodes.filter { infoPattern.matches(it.text.trim()) }
    val nameLabels = nodes.filter { it.text.trim() == "群聊名称" }
    val announcements = nodes.filter { it.text.trim() == "群公告" }
    if (detailTitles.size == 1 && nameLabels.isNotEmpty() && announcements.isNotEmpty()) {
        val label = nameLabels.singleOrNull()
        val announcement = announcements.singleOrNull()
        val count = infoPattern.matchEntire(detailTitles.single().text.trim())!!.groupValues[1].toIntOrNull()
        val layout = label != null && announcement != null && count != null && count >= 2 &&
            label.bounds.top > ticket.height * .20 && label.bounds.left < ticket.width * .25 &&
            announcement.bounds.top > label.bounds.bottom
        val value = if (!layout) null else nodes.filter { n ->
            n.text.isNotBlank() && n.text.trim() !in setOf(">", "›", "〉", "＞") &&
                n.bounds.left >= maxOf(label!!.bounds.right + ticket.width * .04, ticket.width * .35) &&
                kotlin.math.abs(cy(n.bounds) - cy(label.bounds)) <= maxOf((label.bounds.bottom - label.bounds.top) * .35, ticket.height * .008)
        }.singleOrNull()
        val fullName = value?.text?.trim()?.takeIf { it.isNotEmpty() && it.length <= 100 &&
            it.none(Char::isISOControl) && !it.contains('…') && !it.contains('⋯') && !it.contains("...") }
        return snapshot(PacketPage(verifiedGroupDetails = layout, groupDetailsName = fullName,
            groupDetailsMemberCount = count.takeIf { fullName != null }))
    }
    val infoCandidates = nodes.filter { n ->
        cy(n.bounds) < ticket.height * .16 && n.bounds.left >= ticket.width * .82 && labels(n).any {
            it in setOf("聊天信息", "聊天信息按钮", "群聊信息", "聊天详情") || infoPattern.matches(it)
        }
    }.mapNotNull(::ancestor).distinctBy { it.path }
    val title = titleNodes.filter { n -> infoCandidates.any { info ->
        kotlin.math.abs(cy(info.bounds) - cy(n.bounds)) <= maxOf((n.bounds.bottom - n.bounds.top) * .65, ticket.height * .012)
    } }.singleOrNull()
    val info = infoCandidates.singleOrNull()
    val chat = title != null && info != null && !nodes.any { labels(it).any { word -> word in setOf("红包详情", "微信红包详情") } }
    val allWords = nodes.flatMap(::labels).toSet()
    if (chat && ticket.packetUi == NativePacketUi.NONE) {
        val header = title!!.text.trim()
        if (header.isBlank() || header in setOf("微信", "WeChat")) return snapshot(PacketPage())
        target("node:info", info!!)
        val cards = nodes.filter { labels(it).contains("微信红包") }.mapNotNull(::ancestor).distinctBy { it.path }
            .filter { card -> card.bounds.right - card.bounds.left < ticket.width * .8 &&
                cx(card.bounds) < ticket.width * .5 && card.bounds.top > title.bounds.bottom &&
                isAvailablePacketCard(words(card), incoming = true) &&
                words(card).none { "发送给" in it || "你发出" in it || "自己发" in it } }
            .sortedBy { it.bounds.top }.map { card -> "node:card:${card.path}".also { target(it, card) } }
        val group = Regex("^.+[（(][0-9]{1,5}[）)]$").matches(header) &&
            Regex("[（(]([0-9]{1,5})[）)]$").find(header)?.groupValues?.get(1)?.toIntOrNull()?.let { it >= 2 } == true ||
            labels(info).any { infoPattern.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull()?.let { count -> count >= 2 } == true }
        return snapshot(PacketPage(chatName = header, groupChat = group, cards = cards,
            chatInfoButton = "node:info", uniqueChatToolbar = true))
    }
    if (infoCandidates.isNotEmpty() || nodes.any { it.editable }) return snapshot(PacketPage())
    // Messages containing result words cannot prove a transaction result without a dedicated sender/amount layout.
    val sender = nodes.filter { Regex("^.{1,80}的红包$").matches(it.text.trim()) &&
        cy(it.bounds) in ticket.height * .12..ticket.height * .35 && cx(it.bounds) in ticket.width * .20..ticket.width * .80 }.singleOrNull()
    val amount = nodes.filter { Regex("^[0-9]+[.][0-9]{2}(?:元)?$").matches(it.text.trim()) &&
        sender != null && it.bounds.top > sender.bounds.bottom && cy(it.bounds) < ticket.height * .50 }.singleOrNull()
    val deposit = nodes.filter { it.text.trim() in setOf("已存入零钱", "已存入零钱，可直接消费") &&
        amount != null && it.bounds.top > amount.bounds.bottom && cy(it.bounds) < ticket.height * .65 }.singleOrNull()
    if (ticket.packetUi == NativePacketUi.DETAIL && sender != null && amount != null && deposit != null) return snapshot(PacketPage(packetPanel = true, result = "已领取"))
    if (ticket.packetUi == NativePacketUi.NONE) return snapshot(PacketPage())
    val packetContext = allWords.any { it == "红包详情" || it == "微信红包" ||
        Regex("^.{0,80}(?:发了一个红包|发来一个红包|发出的红包)$").matches(it) || Regex("^.{1,80}的红包$").matches(it) }
    val composer = nodes.any { it.editable }
    if (!packetContext || composer) return snapshot(PacketPage())
    val result = when {
        allWords.any { it in setOf("手慢了，红包派完了", "手慢了,红包派完了", "已被领完", "红包已被领完") } -> "已被领完"
        allWords.any { it in setOf("该红包已过期", "红包已过期", "已过期", "超过24小时，红包已过期") } -> "已过期"
        else -> null
    }
    val opens = if (result != null || ticket.packetUi != NativePacketUi.RECEIVE) emptyList() else nodes.filter { labels(it).any { word -> word in setOf("开", "開", "开红包", "拆红包", "打开红包") } }
        .mapNotNull(::ancestor).distinctBy { it.path }.filter { n -> cx(n.bounds) in ticket.width * .30..ticket.width * .70 &&
            cy(n.bounds) in ticket.height * .30..ticket.height * .80 && n.bounds.right-n.bounds.left <= ticket.width*.50 &&
            n.bounds.bottom-n.bounds.top <= ticket.height*.35 }
    val open = opens.singleOrNull()
    if (open != null) target("node:open", open)
    return snapshot(PacketPage(packetPanel = true, openButton = open?.let { "node:open" }, result = result))
}
