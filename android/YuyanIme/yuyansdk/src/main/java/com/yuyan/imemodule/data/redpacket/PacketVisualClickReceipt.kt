package com.yuyan.imemodule.data.redpacket

import com.yuyan.imemodule.data.capture.ui.IntRect

internal enum class PacketVisualReceiptCheck { Ready, Wait, Stop }

internal class PacketVisualClickReceipt {
    private enum class Destination { GroupDetails, PacketPanel, PacketResult }
    private data class Expected(val window: Int, val target: IntRect, val destination: Destination, val at: Long)
    private data class Pending(val destination: Destination, val at: Long)
    private var expected: Expected? = null
    private var pending: Pending? = null

    fun expect(window: Int, target: IntRect, id: String, now: Long) {
        // 无来源回执尚未确认时，不能用下一次操作替换它。
        if (pending != null) return
        val destination = when {
            id == "visual:info" -> Destination.GroupDetails
            id.startsWith("visual:card:") -> Destination.PacketPanel
            id == "visual:open" -> Destination.PacketResult
            else -> null
        }
        expected = if (destination != null && target.nonEmpty()) Expected(window, target, destination, now) else null
    }

    fun consume(window: Int, source: IntRect?, now: Long): Boolean {
        val receipt = expected ?: return false
        expected = null
        if (receipt.window != window || now - receipt.at !in 0..600) return false
        if (source != null && source.nonEmpty()) {
            val centerX = receipt.target.left + (receipt.target.right - receipt.target.left) / 2.0
            val centerY = receipt.target.top + (receipt.target.bottom - receipt.target.top) / 2.0
            return centerX >= source.left && centerX < source.right && centerY >= source.top && centerY < source.bottom
        }
        pending = Pending(receipt.destination, now)
        return true
    }

    fun verify(page: PacketPage, now: Long): PacketVisualReceiptCheck {
        val confirmation = pending ?: return PacketVisualReceiptCheck.Ready
        val timeout = if (confirmation.destination == Destination.PacketResult) 5_000 else 2_500
        if (now - confirmation.at !in 0 until timeout) return PacketVisualReceiptCheck.Stop
        val arrived = when (confirmation.destination) {
            Destination.GroupDetails -> page.verifiedGroupDetails
            Destination.PacketPanel -> page.packetPanel
            Destination.PacketResult -> page.packetPanel && page.result != null
        }
        if (!arrived) return PacketVisualReceiptCheck.Wait
        pending = null
        return PacketVisualReceiptCheck.Ready
    }

    fun clear() {
        expected = null
        pending = null
    }
}

private fun IntRect.nonEmpty(): Boolean = right > left && bottom > top
