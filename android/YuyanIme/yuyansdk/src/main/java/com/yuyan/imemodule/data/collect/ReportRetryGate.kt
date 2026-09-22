package com.yuyan.imemodule.data.collect

/** 图片成功节流不能抢掉常规同步；忙碌时保留常规待办，失败退避仍约束两者。 */
internal class ReportRetryGate {
    private data class Retry(val until: Long = 0, val failed: Boolean = false, val regularPending: Boolean = false)
    private val retries = HashMap<String, Retry>()

    @Synchronized fun record(target: String, until: Long, failed: Boolean, regularCompleted: Boolean = false) {
        val pending = !regularCompleted && retries[target]?.regularPending == true
        retries[target] = Retry(until, failed, pending)
    }

    @Synchronized fun requestRegular(target: String) {
        retries[target] = (retries[target] ?: Retry()).copy(regularPending = true)
    }

    @Synchronized fun regularPending(target: String): Boolean = retries[target]?.regularPending == true

    @Synchronized fun blocks(target: String, now: Long, regularSync: Boolean): Boolean =
        retries[target]?.let { now < it.until && (it.failed || !regularSync) } ?: false
}
