package com.yuyan.imemodule.data.redpacket

import android.os.SystemClock
import com.yuyan.imemodule.data.capture.ui.IntRect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal enum class SilentPacketReadResult { VALID, FRAME_STALE, FRAME_INVALID, READ_REJECTED, READ_FAILED, PROTECTED }
internal data class SilentPacketPageFacts(val titlePresent: Boolean, val groupHint: Boolean,
    val infoTargetPresent: Boolean, val verifiedGroupDetails: Boolean, val packetPanel: Boolean,
    val availableCardCount: Int, val uniqueChatToolbar: Boolean = false,
    val detailsNamePresent: Boolean = false, val detailsNameExact: Boolean? = null,
    val detailsMemberCount: Int? = null)
internal data class SilentPacketReadDiagnostic(val result: SilentPacketReadResult, val elapsedMs: Long,
    val frameAgeMs: Long, val facts: SilentPacketPageFacts? = null,
    val sourceFacts: SilentPacketSourceFacts? = null,
    val groupComparison: SilentPacketGroupComparison? = null) {
    fun renderFacts(): String = facts?.let {
        "页面证据：titlePresent=${it.titlePresent} groupHint=${it.groupHint} " +
            "infoTargetPresent=${it.infoTargetPresent} verifiedGroupDetails=${it.verifiedGroupDetails} " +
            "packetPanel=${it.packetPanel} availableCardCount=${it.availableCardCount}" +
            " uniqueChatToolbar=${it.uniqueChatToolbar} detailsNamePresent=${it.detailsNamePresent}" +
            " detailsNameExact=${it.detailsNameExact} detailsMemberCount=${it.detailsMemberCount}" +
            (sourceFacts?.let { source ->
                " titleCandidateCount=${source.titleCandidateCount} menuDotCount=${source.menuDotCount}" +
                    " groupSuffixCount=${source.groupSuffixCount} bottomWechatTabsPresent=${source.bottomWechatTabsPresent}" +
                    " orangeRegionCount=${source.orangeRegionCount}" +
                    " loginPromptPresent=${source.loginPromptPresent} loadingPromptPresent=${source.loadingPromptPresent}" +
                    " networkErrorPresent=${source.networkErrorPresent} permissionPromptPresent=${source.permissionPromptPresent}" +
                    " chatComposerPresent=${source.chatComposerPresent}" +
                    " recognizedLineCount=${source.recognizedLineCount} contentLineCount=${source.contentLineCount}" +
                    " wechatTitleLabelPresent=${source.wechatTitleLabelPresent}" +
                    " fixedKeywords=${source.fixedKeywordNames().joinToString(prefix = "[", postfix = "]")}"
            } ?: " sourceFacts=UNAVAILABLE") +
            (groupComparison?.let { comparison ->
                " groupObservedPresent=${comparison.observedPresent}" +
                    " groupExpectedCharacters=${comparison.expectedCharacterCount}" +
                    " groupObservedCharacters=${comparison.observedCharacterCount}" +
                    " groupExactEqual=${comparison.exactEqual}" +
                    " groupWhitespaceEqual=${comparison.whitespaceEqual}" +
                    " groupNormalizedEqual=${comparison.normalizedEqual}"
            } ?: " groupComparison=UNAVAILABLE")
    } ?: "页面证据：未验证"
}

/** 只返回状态、耗时及固定页面事实；不输出文字、图片或窗口身份，不放宽1秒时效。 */
internal suspend fun inspectSilentPacketFrame(frame: SilentPacketFrame, allowed: () -> Boolean,
    read: suspend (SilentPacketFrame) -> PacketVisualSnapshot?, clock: () -> Long = SystemClock::uptimeMillis
): SilentPacketReadDiagnostic {
    val started = clock()
    fun result(value: SilentPacketReadResult, now: Long = clock(), facts: SilentPacketPageFacts? = null,
        sourceFacts: SilentPacketSourceFacts? = null): SilentPacketReadDiagnostic {
        return SilentPacketReadDiagnostic(value, (now - started).coerceAtLeast(0), now - frame.capturedAt, facts, sourceFacts)
    }
    try {
        currentCoroutineContext().ensureActive()
        if (!allowed()) return result(SilentPacketReadResult.PROTECTED)
        val bitmap = frame.bitmap
        if (bitmap.isRecycled || frame.displayId <= 0 || frame.frameId <= 0 ||
            bitmap.width !in 1..4096 || bitmap.height !in 1..8192 ||
            bitmap.width.toLong() * bitmap.height > 8_388_608)
            return result(SilentPacketReadResult.FRAME_INVALID)
        if (started - frame.capturedAt !in 0..1_000)
            return result(SilentPacketReadResult.FRAME_STALE)
        // Reader会回收输入Bitmap，提前保留用于一致性校验的尺寸。
        val bounds = IntRect(0, 0, bitmap.width, bitmap.height)
        val snapshot = read(frame)
        currentCoroutineContext().ensureActive()
        if (!allowed()) return result(SilentPacketReadResult.PROTECTED)
        val now = clock()
        if (now < started || now - frame.capturedAt !in 0..1_000)
            return result(SilentPacketReadResult.FRAME_STALE, now)
        if (snapshot == null || snapshot.windowId != frame.displayId || snapshot.bounds != bounds ||
            snapshot.originX != 0 || snapshot.originY != 0 || snapshot.capturedAt != frame.capturedAt)
            return result(SilentPacketReadResult.READ_REJECTED, now)
        val page = snapshot.match.page
        return result(SilentPacketReadResult.VALID, now, SilentPacketPageFacts(
            !page.chatName.isNullOrBlank(), page.groupChat,
            page.chatInfoButton?.let { it in snapshot.match.targets } == true,
            page.verifiedGroupDetails, page.packetPanel, page.cards.size, page.uniqueChatToolbar,
            !page.groupDetailsName.isNullOrBlank(), null, page.groupDetailsMemberCount), snapshot.sourceFacts)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        return result(SilentPacketReadResult.READ_FAILED)
    } finally {
        if (!frame.bitmap.isRecycled) frame.bitmap.recycle()
    }
}
