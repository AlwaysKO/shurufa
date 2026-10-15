package com.yuyan.imemodule.data.capture.page

internal data class VideoCallScreenshotTicket(val windowId: Int, val generation: Long, val observedAt: Long)

/** 一次已确认的视频通话画面停留；状态只在内存，不生成无图记录。 */
internal class WechatVideoCallScreenshotSchedule {
    private var generation = 0L
    private var pending: VideoCallScreenshotTicket? = null
    private var attempted = false
    private var savedWindow: Int? = null
    private var notifiedVideoActive = false

    /** 接通通知仅触发一次重新核验；结束只撤销旧候选，不否决未来独立确认的画面。 */
    @Synchronized fun onCallSignal(active: Boolean, source: String): Boolean {
        val video = active && source == "wechat_video"
        val recheck = video && !notifiedVideoActive
        notifiedVideoActive = video
        if (!video) leave()
        return recheck
    }

    @Synchronized fun observe(packageName: String, windowId: Int, elapsed: Long): VideoCallScreenshotTicket? {
        if (packageName != "com.tencent.mm" || windowId < 0 || elapsed < 0 || savedWindow == windowId) return null
        if (pending?.windowId == windowId) return null
        attempted = false
        return VideoCallScreenshotTicket(windowId, ++generation, elapsed).also { pending = it }
    }
    @Synchronized fun isCurrent(ticket: VideoCallScreenshotTicket): Boolean = pending == ticket
    @Synchronized fun take(ticket: VideoCallScreenshotTicket, elapsed: Long): Boolean {
        if (pending != ticket || attempted || elapsed < ticket.observedAt || elapsed - ticket.observedAt < 5_000L) return false
        attempted = true
        return true
    }
    @Synchronized fun saved(ticket: VideoCallScreenshotTicket) {
        if (pending == ticket && attempted) savedWindow = ticket.windowId
    }
    @Synchronized fun invalidate() { pending = null; attempted = false; generation++ }
    @Synchronized fun leave() { invalidate(); savedWindow = null }
}
