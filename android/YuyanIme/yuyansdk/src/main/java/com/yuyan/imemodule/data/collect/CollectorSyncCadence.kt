package com.yuyan.imemodule.data.collect

/** 各类批次独立节流；不控制本地落盘和用户主动请求。 */
internal class CollectorSyncCadence(
    private val wifiIntervalMs: Long = 60_000L,
    private val mobileIntervalMs: Long = 15 * 60_000L,
    private val now: () -> Long,
) {
    private var lastStarted: Long? = null
    @Synchronized fun tryStart(wifi: Boolean, userInitiated: Boolean = false): Boolean {
        val current=now()
        val elapsed=lastStarted?.let { current-it }
        val interval=if(wifi)wifiIntervalMs else mobileIntervalMs
        if(!userInitiated && elapsed!=null && elapsed in 0 until interval)return false
        lastStarted=current
        return true
    }
    @Synchronized fun wakeDelay(wifi: Boolean): Long {
        val elapsed=lastStarted?.let { now()-it } ?: return 5_000L
        val interval=if(wifi)wifiIntervalMs else mobileIntervalMs
        return if(elapsed<0)5_000L else (interval-elapsed).coerceAtLeast(5_000L)
    }
}
