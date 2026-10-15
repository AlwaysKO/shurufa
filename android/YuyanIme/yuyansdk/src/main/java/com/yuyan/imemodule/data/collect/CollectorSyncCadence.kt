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
    /** 精确只读到期查询；不消费上传资格，也不因切换网络重置时钟。 */
    @Synchronized fun remainingDelay(wifi: Boolean): Long {
        val elapsed=lastStarted?.let { now()-it } ?: return 0L
        val interval=if(wifi)wifiIntervalMs else mobileIntervalMs
        return if(elapsed<0)0L else (interval-elapsed).coerceAtLeast(0L)
    }
    @Synchronized fun wakeDelay(wifi: Boolean): Long = remainingDelay(wifi).coerceAtLeast(5_000L)
}
