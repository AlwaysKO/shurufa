package com.yuyan.imemodule.data.capture.page

import android.util.Log
import java.util.Locale

/** 本地短枚举日志，不含包名之外的页面标识、正文、OCR文本或图片；不另启上传。 */
internal enum class PageProbeStatus {
    SLOT_BUSY, PREPARATION_BLOCKED, AUTHORIZATION_LOST, BACKGROUND_PAUSED, SCOPE_LOST,
    GUARD_REJECTED, CHAT_ATTEMPT_REJECTED, SYSTEM_FAILED, SYSTEM_UNSUPPORTED,
    PREFLIGHT_REJECTED, INVALID_CROP, OCR_EMPTY, CLASSIFICATION_REJECTED,
    ENCODING_FAILED, IMAGE_TOO_LARGE, FRAME_ACCEPTED, CANCELLED, CAPTURE_EXCEPTION,
}

internal data class FeedNavigationEvidence(val labelCount: Int,
    val topFollow: Boolean, val topRecommend: Boolean, val topFriend: Boolean, val topDrama: Boolean,
    val bottomHome: Boolean, val bottomMessage: Boolean, val bottomMe: Boolean, val bottomFollow: Boolean)

internal object PageProbeDiagnostics {
    private val reasons = setOf("unsupported_package", "secure_window", "sensitive_input",
        "editable_non_chat", "invalid_bounds", "verified_chat_adapter", "insufficient_evidence", "live_not_single_video")
    fun feedNavigation(evidence: FeedNavigationEvidence) {
        fun bit(value: Boolean) = if (value) 1 else 0
        Log.i("BrowsingPageProbe", "feed_navigation:labels=${evidence.labelCount.coerceIn(0, 10000)}" +
            ",top_follow=${bit(evidence.topFollow)},top_recommend=${bit(evidence.topRecommend)}" +
            ",top_friend=${bit(evidence.topFriend)},top_drama=${bit(evidence.topDrama)}" +
            ",bottom_home=${bit(evidence.bottomHome)},bottom_message=${bit(evidence.bottomMessage)}" +
            ",bottom_me=${bit(evidence.bottomMe)},bottom_follow=${bit(evidence.bottomFollow)}")
    }
    fun report(status: PageProbeStatus, reason: String? = null, errorCode: Int? = null) {
        val detail = if (errorCode != null && status == PageProbeStatus.SYSTEM_FAILED) ":$errorCode"
            else if (reason != null) ":${reason.takeIf { it in reasons } ?: "unknown"}" else ""
        Log.i("BrowsingPageProbe", status.name.lowercase(Locale.ROOT) + detail)
    }
}
