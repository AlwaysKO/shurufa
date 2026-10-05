package com.yuyan.imemodule.data.redpacket

import com.yuyan.imemodule.data.capture.ui.IntRect

internal data class PacketVisualSnapshot(
    val match: PacketVisualMatch, val windowId: Int, val bounds: IntRect,
    val originX: Int, val originY: Int, val capturedAt: Long,
    val signatures: Map<String, String> = emptyMap(),
)

internal fun confirmedPacketTarget(first: PacketVisualSnapshot, second: PacketVisualSnapshot, id: String, now: Long): IntRect? {
    if (first.windowId != second.windowId || first.bounds != second.bounds ||
        first.originX != second.originX || first.originY != second.originY ||
        now - first.capturedAt !in 0..4_000 || now - second.capturedAt !in 0..1_000) return null
    val a = first.match.page; val b = second.match.page
    if (a.chatName != b.chatName || a.groupChat != b.groupChat || a.packetPanel != b.packetPanel ||
        a.verifiedGroupDetails != b.verifiedGroupDetails || b.result != null) return null
    val original = first.match.targets[id] ?: return null
    val candidates = second.match.targets.filter { (key, box) ->
        (key == id || id.startsWith("visual:card:") && key.startsWith("visual:card:")) &&
            kotlin.math.abs(box.left-original.left) <= 5 && kotlin.math.abs(box.top-original.top) <= 5 &&
            kotlin.math.abs(box.right-original.right) <= 5 && kotlin.math.abs(box.bottom-original.bottom) <= 5
    }
    val current = candidates.entries.singleOrNull() ?: return null
    if (first.signatures[id] != second.signatures[current.key]) return null
    val rect = current.value
    return IntRect(rect.left+second.originX,rect.top+second.originY,rect.right+second.originX,rect.bottom+second.originY)
}
