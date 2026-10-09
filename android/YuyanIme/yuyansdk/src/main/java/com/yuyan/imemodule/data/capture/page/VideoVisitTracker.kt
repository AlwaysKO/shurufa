package com.yuyan.imemodule.data.capture.page

import java.util.UUID
import kotlinx.serialization.Serializable

internal enum class VideoExitReason { SWITCHED, EXIT, BACKGROUND, LOCKED, INTERRUPTED, PAGE_CHANGED }
internal enum class VideoObservationKind { CONFIRMED_VIDEO, UNCONFIRMED_FEED }
internal enum class VideoFrameRole { FIRST, LAST }

/** 可持久化的值快照；不是播放进度。由调用者在确认页面/视频证据后创建。 */
@Serializable
internal data class VideoVisit(
    val id: String,
    val platform: String,
    val videoKey: String,
    val enteredAt: Long,
    val enteredElapsed: Long,
    val lastObservedElapsed: Long = enteredElapsed,
    val firstImage: String? = null,
    val lastImage: String? = null,
    val endedAt: Long? = null,
    val durationMillis: Long? = null,
    val reason: VideoExitReason? = null,
    val complete: Boolean = false,
    val observationKind: VideoObservationKind = VideoObservationKind.CONFIRMED_VIDEO,
)

internal data class VideoVisitChange(
    val active: VideoVisit,
    val started: Boolean,
    val previous: VideoVisit? = null,
)

/**
 * 只负责一次前台访问的计时/图片归属，不启动定时器、不截图、不联网。
 * 页面识别、滚动突发合并与持久化由服务层负责，不能把普通内容更新直接作为 enter。
 */
internal class VideoVisitTracker(initial: VideoVisit? = null) {
    init {
        initial?.let {
            require(it.id.isNotBlank() && it.videoKey.isNotBlank())
            require(it.platform == "wechat" || it.platform == "douyin")
            require(it.enteredElapsed >= 0 && it.lastObservedElapsed >= it.enteredElapsed)
            require(it.reason == null && it.endedAt == null && it.durationMillis == null && !it.complete)
        }
    }
    private var active: VideoVisit? = initial

    @Synchronized fun enter(
        platform: String,
        videoKey: String,
        elapsed: Long,
        wallTime: Long,
        observationKind: VideoObservationKind = VideoObservationKind.CONFIRMED_VIDEO,
    ): VideoVisitChange {
        require(platform == "wechat" || platform == "douyin")
        require(videoKey.isNotBlank())
        require(elapsed >= 0)
        val current = active
        if (current != null && current.platform == platform && current.videoKey == videoKey && current.observationKind == observationKind) {
            val updated = current.copy(lastObservedElapsed = maxOf(current.lastObservedElapsed, elapsed))
            active = updated
            return VideoVisitChange(updated, false)
        }
        val previous = current?.let { finish(it.id, elapsed, wallTime, if (it.observationKind == VideoObservationKind.UNCONFIRMED_FEED) VideoExitReason.PAGE_CHANGED else VideoExitReason.SWITCHED) }
        val next = VideoVisit(UUID.randomUUID().toString(), platform, videoKey, wallTime, elapsed, observationKind = observationKind)
        active = next
        return VideoVisitChange(next, true, previous)
    }

    /** 仅接受本次访问、已再次核对视频身份的帧；首尾各最多一张。 */
    @Synchronized fun attachFrame(
        visitId: String,
        observedVideoKey: String,
        role: VideoFrameRole,
        imageReference: String,
    ): Boolean {
        val current = active ?: return false
        if (current.id != visitId || current.videoKey != observedVideoKey || imageReference.isBlank()) return false
        active = when (role) {
            VideoFrameRole.FIRST -> if (current.firstImage == null) current.copy(firstImage = imageReference) else return false
            VideoFrameRole.LAST -> if (current.lastImage == null) current.copy(lastImage = imageReference) else return false
        }
        return true
    }

    @Synchronized fun snapshot(): VideoVisit? = active

    @Synchronized fun finish(expectedVisitId: String, elapsed: Long, wallTime: Long, reason: VideoExitReason): VideoVisit? {
        val current = active ?: return null
        if (current.id != expectedVisitId) return null
        active = null
        if (reason == VideoExitReason.INTERRUPTED) return interrupted(current)
        val validClock = elapsed >= current.lastObservedElapsed
        return current.copy(
            endedAt = wallTime,
            durationMillis = if (validClock) elapsed - current.enteredElapsed else null,
            reason = reason,
            complete = validClock,
        )
    }

    /** 进程恢复不能沿用旧 elapsed 或把恢复时刻当成离开时刻。 */
    fun interrupted(saved: VideoVisit): VideoVisit = if (saved.reason != null) saved else saved.copy(
        endedAt = null,
        durationMillis = null,
        reason = VideoExitReason.INTERRUPTED,
        complete = false,
    )
}
