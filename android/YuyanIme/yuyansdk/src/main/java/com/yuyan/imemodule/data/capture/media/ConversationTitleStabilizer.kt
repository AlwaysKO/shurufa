package com.yuyan.imemodule.data.capture.media

import com.yuyan.imemodule.data.capture.model.ChatPlatform
import com.yuyan.imemodule.data.capture.model.ConversationType
import com.yuyan.imemodule.data.capture.sha256
import java.text.Normalizer

/** 临时证据仅限连续页面；已确认精确字形可持久复用，不做全局名字模糊合并。 */
internal open class ConversationTitleStabilizer(
    private val platform: ChatPlatform,
    private val accountKey: String,
    private val source: String,
    private val legacyWechat: Boolean = false,
    private val identityStore: ConversationIdentityStore = MemoryConversationIdentityStore(),
) {
    private data class State(
        val key: String,
        var visualKey: String,
        var candidate: ScreenshotConversationIdentity,
        var votes: Int,
        var votedAt: Long,
        var observedAt: Long,
        var confirmed: ScreenshotConversationIdentity? = null,
        val previousKey: String? = null,
    )
    private var state: State? = null
    private var generation = 0L
    private var pendingKey: String? = null
    private var pendingAt = 0L
    private val scope = "${platform.wireName}|$accountKey|$source"

    @Synchronized fun version(): Long = generation
    @Synchronized fun reset() { generation++; state = null; pendingKey = null }

    @Synchronized fun observe(
        title: String?,
        visualKey: String?,
        nowMillis: Long,
        expectedVersion: Long = generation,
    ): ScreenshotConversationIdentity {
        // OCR 在后台运行，导航/锁屏之后才返回的结果不得污染新页面的状态。
        if (expectedVersion != generation) return unresolved()
        val previous = state?.takeIf { nowMillis - it.observedAt in 0..CONTINUITY_MILLIS }
        val normalized = normalizeConversationTitle(title, platform)
        if (normalized == null && isTransientConversationTitle(title) && previous != null) {
            // 正在输入/在线不是联系人改名，不能投票或确认，也不能跨导航复用。
            return identity(previous, title)
        }
        if (normalized == null || normalized.contains("…") || normalized.contains("...") || visualKey == null || !visualKey.matches(Regex("[a-f0-9]{64}"))) {
            state = null
            if (pendingKey == null || nowMillis - pendingAt !in 0..CONTINUITY_MILLIS) {
                pendingKey = (if (legacyWechat) "screenshot-v2:" else "capture-v3:") + "pending:" + java.util.UUID.randomUUID()
            }
            pendingAt = nowMillis
            return unresolved(title).copy(externalKey = pendingKey!!)
        }
        val candidate = if (legacyWechat) screenshotConversationIdentity(normalized, "").copy(source = source)
            else ScreenshotConversationIdentity("", normalized, ConversationType.UNKNOWN, 0.55, source)
        val continuous = previous != null && (
            previous.visualKey == visualKey ||
                canonicalTitle(previous.candidate.displayName) == canonicalTitle(candidate.displayName)
            )
        val known = identityStore.find(scope, visualKey)
        val recoverKey = pendingKey?.takeIf { nowMillis - pendingAt in 0..CONTINUITY_MILLIS }
        val current = if (continuous) previous!! else State(
            // 新命名空间与历史 title:<名字哈希> 隔离，不修改、迁移或合并旧记录。
            key = known?.key ?: recoverKey ?: if (legacyWechat) "screenshot-v2:$visualKey" else
                "capture-v3:" + sha256("${platform.wireName}|$accountKey|$source|$visualKey".toByteArray(Charsets.UTF_8)),
            visualKey = visualKey,
            candidate = candidate,
            votes = 0,
            votedAt = nowMillis - MIN_FRAME_INTERVAL_MILLIS,
            observedAt = nowMillis,
            confirmed = known?.let { candidate.copy(displayName = it.name, conversationType = ConversationType.entries.firstOrNull { type -> type.wireName == it.type } ?: candidate.conversationType) },
            previousKey = recoverKey?.takeIf { known != null && it != known.key },
        ).also { state = it }
        if (canonicalTitle(current.candidate.displayName) != canonicalTitle(candidate.displayName)) {
            current.candidate = candidate
            current.votes = 0
        }
        if (nowMillis - current.votedAt >= MIN_FRAME_INTERVAL_MILLIS) {
            current.votes++
            current.votedAt = nowMillis
            if (current.votes >= 2) current.confirmed = candidate
        }
        pendingKey = null
        current.confirmed?.takeIf { canonicalTitle(it.displayName) == canonicalTitle(candidate.displayName) }?.let {
            identityStore.save(scope, visualKey, StoredConversationIdentity(current.key, it.displayName, it.conversationType.wireName))
        }
        current.visualKey = visualKey
        current.observedAt = nowMillis
        return identity(current, candidate.displayName)
    }

    private fun unresolved(title: String? = null): ScreenshotConversationIdentity =
        unresolvedWechatScreenshotIdentity(title).let {
            if (legacyWechat) it else it.copy(externalKey = it.externalKey.replace("screenshot-pending:", "capture-pending:"))
        }

    private fun identity(current: State, observedTitle: String?): ScreenshotConversationIdentity {
        val confirmed = current.confirmed
        return (confirmed ?: current.candidate).copy(
            externalKey = current.key,
            displayName = confirmed?.displayName ?: "待确认会话 ${current.key.takeLast(8)}",
            // 这是分组证据等级，不冒充 ML Kit 文字识别概率。
            confidence = if (confirmed == null) 0.55 else 0.85,
            status = if (confirmed == null) "pending" else "confirmed",
            observedTitle = observedTitle,
            previousKey = current.previousKey,
        )
    }

    private fun canonicalTitle(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(java.util.Locale.ROOT)

    private companion object {
        const val MIN_FRAME_INTERVAL_MILLIS = 350L
        const val CONTINUITY_MILLIS = 5 * 60 * 1000L
    }
}
