package com.yuyan.imemodule.data.capture.page

/**
 * 生命周期结束入口：只消费已核实的前台窗口元信息，不读取其他 App 内容。
 * 调用方必须在事件发生时冻结访问 ID 和两个时钟，后台延迟/重试不可改用执行时刻。
 * 不是系统监听器；服务接线仍需区分应用窗口、输入法和系统覆盖层。
 */
internal class VideoVisitLifecycle(private val store: VideoVisitStore) {
    fun foreground(
        expectedVisitId: String,
        packageName: String?,
        applicationWindow: Boolean,
        home: Boolean,
        elapsed: Long,
        wallTime: Long,
    ): VideoVisit? {
        if (!applicationWindow || packageName.isNullOrBlank()) return null
        val current = store.active() ?: return null
        if (current.id != expectedVisitId) return null
        val host = when (current.platform) {
            "wechat" -> "com.tencent.mm"
            "douyin" -> "com.ss.android.ugc.aweme"
            else -> return null
        }
        if (host == packageName) return null
        return store.finish(expectedVisitId, elapsed, wallTime,
            if (home) VideoExitReason.EXIT else VideoExitReason.BACKGROUND)
    }

    /** 熄屏即可结束，不等待锁屏广播或新的窗口事件。 */
    fun screenOff(expectedVisitId: String, elapsed: Long, wallTime: Long): VideoVisit? =
        store.finish(expectedVisitId, elapsed, wallTime, VideoExitReason.LOCKED)

    /** 服务中断不能作为宿主的准确退出时刻；下次初始化另调用 recoverInterrupted。 */
    fun interrupted(expectedVisitId: String): VideoVisit? {
        val current = store.active() ?: return null
        return store.finish(expectedVisitId, current.lastObservedElapsed, current.enteredAt, VideoExitReason.INTERRUPTED)
    }
}
