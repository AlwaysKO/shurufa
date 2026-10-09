package com.yuyan.imemodule.data.capture.page

/** generation 必须是调度器生命周期内单调递增的导航代次，不是视频ID或页面像素哈希。 */
internal data class BrowsePageToken(val packageName: String, val windowId: Int, val generation: Long)

/**
 * 普通浏览的单槽、事件驱动调度；无定时器、不取帧、不处理聊天发送或视频首尾。
 * 只有可信的页面变化/停滚入口调用 changed，不能把视频播放内容变化直接送入。
 * 后台串行使用；reserveAttempt 必须原子持久预留设备共享预算，未接入前禁止启用。
 * 此处三分钟间隔仅是进程内额外保护，跨进程预算/间隔由预留实现保证。
 */
internal class BrowsingCaptureSchedule {
    private data class Candidate(val page: BrowsePageToken, val changedAt: Long)
    private var candidate: Candidate? = null
    private var latestEvent: Candidate? = null
    private var consumedThrough = -1L
    private var reserving = false
    private var newestGeneration = -1L
    private var closedGeneration = -1L
    private var lastAttempt: Long? = null
    private var revision = 0L

    @Synchronized fun changed(page: BrowsePageToken, elapsed: Long) {
        // 新导航可能与上一帧消费落在同一毫秒；只禁止同代次重放，不丢掉明确的新代次。
        if (elapsed < 0 || elapsed < consumedThrough ||
            (elapsed == consumedThrough && page.generation <= newestGeneration) ||
            page.generation < 0 || page.generation < newestGeneration ||
            page.generation <= closedGeneration) return
        if (page.packageName !in setOf("com.tencent.mm", "com.ss.android.ugc.aweme")) {
            leave(page.generation)
            return
        }
        // 已消费候选与事件高水位分离，迟到事件不能在候选清空后复活旧页面。
        val previous = latestEvent
        if (previous != null && (elapsed < previous.changedAt ||
            (page.generation == previous.page.generation &&
                (previous.page != page || elapsed == previous.changedAt)))) return
        newestGeneration = page.generation
        candidate = Candidate(page, elapsed)
        latestEvent = candidate
        revision++
    }

    /** 离开/熄屏/撤同意时调用；迟到旧事件不能复活已关闭代次。 */
    @Synchronized fun leave(generation: Long) {
        closedGeneration = maxOf(closedGeneration, generation)
        newestGeneration = maxOf(newestGeneration, generation)
        if ((candidate?.page?.generation ?: Long.MAX_VALUE) <= closedGeneration) candidate = null
        revision++
    }

    /** 单次候选的延后机会；没有候选时不创建定时任务。用差值避免绝对截止时间溢出。 */
    @Synchronized fun remainingDelay(elapsed: Long): Long? {
        val pending = candidate ?: return null
        val stable = if (elapsed < pending.changedAt) 800L else (800L - (elapsed - pending.changedAt)).coerceAtLeast(0)
        val interval = lastAttempt?.let {
            if (elapsed < it) 180_000L else (180_000L - (elapsed - it)).coerceAtLeast(0)
        } ?: 0L
        return maxOf(stable, interval)
    }

    /**
     * current 必须由调用方重新核实窗口和代次，allowed 包含同意、输入/游戏、功耗守卫。
     * 返回值是一次性尝试许可，不是截图成功；失败和重复不得退还预算/间隔。
     * 取帧前及回调后仍须再次校验 current（此方法无法冻结系统导航）。
     */
    @Synchronized fun take(
        elapsed: Long,
        current: BrowsePageToken?,
        allowed: Boolean,
        reserveAttempt: () -> Boolean,
    ): BrowsePageToken? {
        if (reserving) return null
        val pending = candidate ?: return null
        if (pending.page != current) {
            leave(pending.page.generation)
            return null
        }
        if (!allowed || elapsed < pending.changedAt || elapsed - pending.changedAt < 800) return null
        if (lastAttempt?.let { elapsed < it || elapsed - it < 180_000 } == true) return null
        candidate = null // 无论预算拒绝/异常，均须新事件，不轮询同一页面。
        consumedThrough = maxOf(consumedThrough, elapsed)
        val expectedRevision = revision
        reserving = true
        try {
            if (!reserveAttempt()) return null
            lastAttempt = elapsed
            if (revision != expectedRevision) return null
            return pending.page
        } finally {
            reserving = false
        }
    }
}
