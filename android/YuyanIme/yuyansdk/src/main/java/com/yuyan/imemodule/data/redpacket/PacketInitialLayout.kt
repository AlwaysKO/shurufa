package com.yuyan.imemodule.data.redpacket

/** 自动导航后只接收一次新窗口的静止布局事件，不豁免实际滚动。 */
internal class PacketInitialLayout {
    private var expected: Pair<Int, Long>? = null
    fun expect(window: Int, now: Long) { expected = window to now }
    fun consume(window: Int, now: Long, stationary: Boolean): Boolean {
        val receipt = expected ?: return false
        expected = null
        return stationary && window != receipt.first && window >= 0 && now - receipt.second in 0..2500
    }
    fun clear() { expected = null }
}
