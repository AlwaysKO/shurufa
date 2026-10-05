package com.yuyan.imemodule.data.redpacket

import com.yuyan.imemodule.data.capture.ui.IntRect

internal data class PacketVisualLine(val text: String, val bounds: IntRect)
internal data class PacketVisualFrame(
    val width: Int,
    val height: Int,
    val lines: List<PacketVisualLine>,
    val orangeRegions: List<IntRect> = emptyList(),
    val redRegions: List<IntRect> = emptyList(),
    val menuDots: List<IntRect> = emptyList(),
)
internal data class PacketVisualMatch(val page: PacketPage, val targets: Map<String, IntRect>)

/** 仅解释端侧识别结果；群人数只是提示，领取仍由 PacketFlow 的群资料验证把关。 */
internal fun parsePacketVisualFrame(frame: PacketVisualFrame): PacketVisualMatch {
    val unknown = PacketVisualMatch(PacketPage(), emptyMap())
    if (frame.width <= 0 || frame.height <= 0) return unknown
    val width = frame.width.toDouble()
    val height = frame.height.toDouble()
    fun valid(rect: IntRect) = rect.left >= 0 && rect.top >= 0 && rect.right <= frame.width &&
        rect.bottom <= frame.height && rect.right > rect.left && rect.bottom > rect.top
    val lines = frame.lines.filter { valid(it.bounds) && it.text.isNotBlank() }
    fun topTitle(line: PacketVisualLine): Boolean = line.bounds.let {
        it.centerY() in height * .025..height * .14 && it.left >= width * .10 &&
            it.right <= width * .90 && it.height() <= height * .065 && it.width() >= width * .055
    }
    fun menuFor(title: PacketVisualLine): IntRect? = frame.menuDots.firstOrNull { rect ->
        valid(rect) && rect.left >= width * .82 && rect.width() <= width * .14 &&
            rect.height() <= height * .06 && rect.centerY() < height * .16 &&
            kotlin.math.abs(rect.centerY() - title.bounds.centerY()) <=
            maxOf(title.bounds.height() * .65, height * .012)
    }
    fun labelsIn(rect: IntRect) = lines.filter { rect.containsCenter(it.bounds) }
    fun resultFrom(words: List<PacketVisualLine>): String? = when {
        words.any { it.compact().contains("已存入零钱") } -> "已领取"
        words.any { it.compact().contains("手慢了") || it.compact().contains("已被领完") } -> "已被领完"
        words.any { it.compact().contains("已过期") || it.compact().contains("超过24小时") } -> "已过期"
        else -> null
    }

    // 弹层必须同时具有大片红色背景和背景内的红包语义，普通聊天中的“开”没有资格。
    val panel = frame.redRegions.firstOrNull { rect ->
        valid(rect) && rect.width() >= width * .40 && rect.height() >= height * .25 &&
            rect.centerX() in width * .30..width * .70 &&
            labelsIn(rect).any { line ->
                val text = line.compact()
                text.contains("发了一个红包") || text.contains("发出的红包") ||
                    text.contains("发来一个红包") || (text.endsWith("的红包") && text.length in 4..80) || text in setOf("微信红包", "红包详情")
            }
    }
    if (panel != null) {
        val words = labelsIn(panel)
        val result = resultFrom(words)
        val open = if (result == null) words.singleOrNull { line ->
            line.compact() in setOf("开", "開", "拆红包", "打开红包") &&
                line.bounds.centerX() in panel.left + panel.width() * .30..panel.left + panel.width() * .70 &&
                line.bounds.centerY() >= panel.top + panel.height() * .40
        } else null
        return PacketVisualMatch(PacketPage(packetPanel = true, openButton = open?.let { "visual:open" }, result = result),
            open?.let { mapOf("visual:open" to it.bounds) }.orEmpty())
    }

    // 新版领取结果采用短红色页眉、发送人标题、金额和入账提示四项共同确认。
    val redHeader = frame.redRegions.any { valid(it) && it.top <= height * .04 &&
        it.width() >= width * .85 && it.height().toDouble() in height * .06..height * .24 }
    val sender = lines.firstOrNull { Regex("^.{1,80}的红包拼?$").matches(it.compact()) &&
        it.bounds.centerY() in height * .12..height * .27 && it.bounds.centerX() in width * .20..width * .80 }
    if (redHeader && sender != null) {
        val deposit = lines.firstOrNull { it.compact().contains("已存入零钱") &&
            it.bounds.top > sender.bounds.bottom && it.bounds.centerY() < height * .50 }
        val amount = lines.any { Regex("^[0-9]+[.][0-9]{2}元?$").matches(it.compact()) &&
            it.bounds.top > sender.bounds.bottom && it.bounds.bottom < (deposit?.bounds?.top ?: 0) &&
            it.bounds.centerX() in width * .20..width * .80 }
        if (deposit != null && amount) return PacketVisualMatch(PacketPage(packetPanel = true, result = "已领取"), emptyMap())
    }

    val titles = lines.filter(::topTitle)
    val detailTitle = titles.firstOrNull { it.compact() in setOf("红包详情", "微信红包") && menuFor(it) == null }
    if (detailTitle != null) {
        val result = resultFrom(lines.filter { it.bounds.top > detailTitle.bounds.bottom })
        return PacketVisualMatch(PacketPage(packetPanel = true, result = result), emptyMap())
    }

    val infoTitle = titles.firstOrNull { Regex("聊天信息[（(][0-9]{1,5}[）)]").matches(it.compact()) }
    if (infoTitle != null) {
        fun setting(name: String) = lines.firstOrNull { it.compact() == name &&
            it.bounds.top > maxOf(infoTitle.bounds.bottom.toDouble(), height * .20) &&
            it.bounds.left < width * .25 && it.bounds.right < width * .60 }
        val name = setting("群聊名称")
        val announcement = setting("群公告")
        val verified = menuFor(infoTitle) == null && name != null && announcement != null &&
            announcement.bounds.top > name.bounds.bottom
        return PacketVisualMatch(PacketPage(verifiedGroupDetails = verified), emptyMap())
    }

    val groupSuffix = Regex("^(.*?)\\s*[（(]\\s*([0-9]{1,5})\\s*[）)]\\s*$")
    fun groupMatch(line: PacketVisualLine) = groupSuffix.matchEntire(line.text.trim())?.takeIf {
        it.groupValues[1].isNotBlank() && (it.groupValues[2].toIntOrNull() ?: 0) >= 2
    }
    val title = titles.filter { groupMatch(it) != null || menuFor(it) != null }
        .minByOrNull { kotlin.math.abs(it.bounds.centerX() - width / 2) } ?: return unknown
    val group = groupMatch(title)
    val chatName = group?.groupValues?.get(1)?.trim() ?: title.text.trim()
    val targets = linkedMapOf<String, IntRect>()
    val info = menuFor(title)?.also { targets["visual:info"] = it }?.let { "visual:info" }
    val cards = frame.orangeRegions.filter { rect ->
        valid(rect) && rect.left.toDouble() in width * .04..width * .20 && rect.centerX() < width * .52 &&
            rect.width().toDouble() in width * .15..width * .80 && rect.height().toDouble() in height * .025..height * .25 &&
            rect.top > title.bounds.bottom && rect.bottom < height * .94 &&
            isAvailablePacketCard(labelsIn(rect).map { it.compact() }, incoming = true)
    }.distinct().sortedBy { it.top }.map { rect ->
        "visual:card:${rect.left},${rect.top},${rect.right},${rect.bottom}".also { targets[it] = rect }
    }
    return PacketVisualMatch(PacketPage(chatName = chatName, groupChat = group != null,
        cards = cards, chatInfoButton = info), targets)
}

private fun PacketVisualLine.compact(): String = text.replace(Regex("\\s+"), "")
private fun IntRect.width(): Int = right - left
private fun IntRect.height(): Int = bottom - top
private fun IntRect.centerX(): Double = left + width() / 2.0
private fun IntRect.centerY(): Double = top + height() / 2.0
private fun IntRect.containsCenter(other: IntRect): Boolean =
    other.centerX() in left.toDouble()..right.toDouble() && other.centerY() in top.toDouble()..bottom.toDouble()
