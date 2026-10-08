package com.yuyan.imemodule.data.capture.media

import android.graphics.Bitmap
import android.os.SystemClock
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.yuyan.imemodule.data.capture.db.PendingAssetEntity
import com.yuyan.imemodule.data.capture.model.ConversationType
import com.yuyan.imemodule.data.capture.sha256
import com.yuyan.imemodule.data.capture.ui.IntRect
import com.yuyan.imemodule.data.collect.ImageUploadRuntime
import com.yuyan.imemodule.data.collect.GameWorkPausedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.text.Normalizer
import kotlin.coroutines.resume
import kotlin.math.abs

internal data class OcrTextLine(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val symbols: List<OcrTextSymbol> = emptyList(),
)

internal data class OcrTextSymbol(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int)

internal fun requireScreenshotBackgroundWork() {
    try {
        ImageUploadRuntime.requireBackgroundWorkAllowed()
    } catch (paused: GameWorkPausedException) {
        // 截图独立协程以取消结束，不能把游戏避让交给进程未处理异常入口。
        throw CancellationException("Screenshot paused for game").apply { initCause(paused) }
    }
}

internal data class ScreenshotConversationIdentity(
    val externalKey: String,
    val displayName: String,
    val conversationType: ConversationType,
    val confidence: Double,
    val source: String,
    val status: String = "pending",
    val observedTitle: String? = null,
    val previousKey: String? = null,
    val isChatPage: Boolean = true,
    // 本帧原始标题文字像素的精确摘要，不写入身份映射或上传正文。
    val exactTitleHash: String? = null,
)

internal fun selectWechatChatTitle(
    lines: List<OcrTextLine>,
    imageWidth: Int,
    headerHeight: Int,
): String? = selectWechatChatTitleLine(lines, imageWidth, headerHeight)?.text?.trim()

internal fun selectWechatChatTitleLine(
    lines: List<OcrTextLine>,
    imageWidth: Int,
    headerHeight: Int,
): OcrTextLine? = lines.asSequence()
    .map { it to it.text.trim() }
    .filter { (line, text) ->
        text.length in 1..80 && line.top >= headerHeight * 0.18 && line.bottom <= headerHeight &&
            line.bottom - line.top >= headerHeight * 0.12 &&
            abs((line.left + line.right) / 2.0 - imageWidth / 2.0) <= imageWidth * 0.32 &&
            !isWechatHeaderNoise(text) &&
            // 旧资产回退仍可能含系统栏：时钟带图标不能成为会话名；不误伤居中含时间的姓名。
            !(line.top < headerHeight * 0.5 && line.right < imageWidth * 0.35 &&
                Regex("^\\d{1,2}:\\d{2}(?:\\s|$)").containsMatchIn(text))
    }
    .sortedWith(compareBy<Pair<OcrTextLine, String>>(
        { abs((it.first.left + it.first.right) / 2.0 - imageWidth / 2.0) },
        { -((it.first.bottom - it.first.top).coerceAtLeast(0)) },
    ))
    .map { it.first }
    .firstOrNull()

private fun isWechatHeaderNoise(text: String): Boolean =
    text in setOf("返回", "···", "...", "5G", "4G", "く", "〈", "〉", "<", ">", "‹", "›", "←", "→", "×") ||
        text.matches(Regex("^\\d{1,2}:\\d{2}$")) ||
        text.matches(Regex("^\\d{1,3}%$")) ||
        text.matches(Regex("^[（(]\\s*\\d+\\s*[）)]$")) ||
        text.all { it.isDigit() || it in " %:·." }

/** 首次立即探测也可能仍停留在列表；无聊天页证据时只重试，不保存成待确认截图。 */
internal fun isWechatScreenshotChatPage(lines: List<OcrTextLine>, width: Int, headerHeight: Int): Boolean {
    val header = lines.filter { it.top >= headerHeight * 0.18 && it.bottom <= headerHeight }
    // 用户只排除发现页。按主标题识别，不能因为正文出现“发现”而丢掉聊天截图。
    val title = selectWechatChatTitleLine(lines, width, headerHeight)
    if (canonicalWechatPageTitle(title?.text) == "发现") return false
    if (selectWechatChatTitleLine(lines, width, headerHeight) != null) return true
    // 标题暂时不可读，但返回和右上角菜单同时存在时仍可保留待确认首张。
    val back = header.any { it.right < width * 0.22 && it.text.trim() in setOf("返回", "〈", "く", "<", "‹", "←") }
    val menu = header.any { it.left > width * 0.75 && it.text.trim() in setOf("···", "...", "…", "⋯") }
    return back && menu
}

internal fun screenshotConversationIdentity(
    recognizedTitle: String?,
    fallbackHeaderHash: String,
): ScreenshotConversationIdentity {
    val raw = stripWechatTitleDecoration(ConversationTitleSimplifier.simplify(recognizedTitle.orEmpty()).trim().replace(Regex("\\s+"), " "))
    // 荣耀截图中微信群人数偶尔被 OCR 拆成“(6)8”；尾部孤立数字同群人数一起丢弃。
    val groupSuffix = Regex("[（(]\\s*\\d+\\s*[）)](?:\\s*[A-Za-z0-9]{1,2})?$")
    val isGroup = groupSuffix.containsMatchIn(raw)
    val normalized = stripWechatTitleDecoration(raw.replace(groupSuffix, "").trim()).takeIf { it.isNotEmpty() }
    if (normalized != null) {
        val stableTitle = Normalizer.normalize(normalized, Normalizer.Form.NFKC).lowercase()
        val key = sha256(stableTitle.toByteArray(Charsets.UTF_8))
        return ScreenshotConversationIdentity(
            externalKey = "title:$key",
            displayName = normalized,
            conversationType = if (isGroup) ConversationType.GROUP else ConversationType.UNKNOWN,
            confidence = 0.55,
            source = "on_device_title_ocr",
        )
    }
    val hash = fallbackHeaderHash.ifBlank { "unknown" }
    return ScreenshotConversationIdentity(
        externalKey = "header:$hash",
        displayName = "微信会话 ${hash.take(8)}",
        conversationType = ConversationType.UNKNOWN,
        confidence = 0.55,
        source = "header_visual_hash",
    )
}

internal fun wechatScreenshotTitleEvidence(header: Bitmap, title: OcrTextLine, exactBand: Boolean): OcrTextLine? {
    if (exactBand) return wechatTitleEvidenceBounds(header, title)
    // 旧文件含状态栏，不能套用44dp标题带的控件证明，也不能将重复噪声确认成名字。
    val count = Regex("[（(]\\s*\\d+\\s*[）)]").findAll(title.text).lastOrNull()
    val tail = count?.let { title.text.substring(it.range.last + 1).trim() }.orEmpty()
    return title.takeUnless { tail.length in 1..2 }
}

internal interface ScreenshotConversationIdentityResolver {
    fun version(): Long = 0L
    suspend fun resolve(asset: PendingAssetEntity, expectedVersion: Long = version(), titleInput: TitleOcrInput? = null): ScreenshotConversationIdentity
    fun reset() = Unit
    // 未提供独立状态的实现不能在导航后借用当前会话来处理旧帧。
    fun snapshot(keepCurrent: () -> Boolean = { false }): ScreenshotConversationIdentityResolver? = null
}

internal fun observeSnapshotTitle(
    current: ConversationTitleStabilizer,
    snapshot: ConversationTitleStabilizer,
    expectedVersion: Long,
    keepCurrent: () -> Boolean,
    title: String?,
    visualKey: String?,
    nowMillis: Long,
): ScreenshotConversationIdentity = synchronized(current) {
    // 检查与投票使用同一把锁，导航 reset 不能夹在两者之间抹掉已接受帧的归属。
    val target = if (keepCurrent() && current.version() == expectedVersion) current else snapshot
    target.observe(title, visualKey, nowMillis, expectedVersion)
}

internal class MlKitWechatScreenshotIdentityResolver(identityStore: ConversationIdentityStore = MemoryConversationIdentityStore()) : ScreenshotConversationIdentityResolver, Closeable {
    private val stabilizer = WechatTitleStabilizer(identityStore)
    override fun reset() = stabilizer.reset()
    override fun version(): Long = stabilizer.version()

    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    override fun snapshot(keepCurrent: () -> Boolean): ScreenshotConversationIdentityResolver =
        snapshotResolver(stabilizer.fork(), keepCurrent)

    private fun snapshotResolver(
        snapshot: ConversationTitleStabilizer,
        keepCurrent: () -> Boolean,
    ): ScreenshotConversationIdentityResolver {
        val initialVersion = snapshot.version()
        return object : ScreenshotConversationIdentityResolver {
            override fun version(): Long = snapshot.version()
            override fun reset() = snapshot.reset()
            override fun snapshot(keepCurrent: () -> Boolean): ScreenshotConversationIdentityResolver =
                snapshotResolver(snapshot.fork(), keepCurrent)
            override suspend fun resolve(asset: PendingAssetEntity, expectedVersion: Long, titleInput: TitleOcrInput?): ScreenshotConversationIdentity =
                resolveWithStabilizer(asset, expectedVersion, titleInput, snapshot) {
                    expectedVersion == initialVersion && keepCurrent()
                }
        }
    }

    override suspend fun resolve(asset: PendingAssetEntity, expectedVersion: Long, titleInput: TitleOcrInput?): ScreenshotConversationIdentity =
        resolveWithStabilizer(asset, expectedVersion, titleInput, stabilizer)

    // 快照只隔离身份状态，沿用原识别器；其生命周期仍由此 owner 的 close 管理。
    private suspend fun resolveWithStabilizer(
        asset: PendingAssetEntity,
        expectedVersion: Long,
        titleInput: TitleOcrInput?,
        titleStabilizer: ConversationTitleStabilizer,
        keepCurrent: (() -> Boolean)? = null,
    ): ScreenshotConversationIdentity = withContext(Dispatchers.Default) {
        requireScreenshotBackgroundWork()
        val header = (if (titleInput != null) titleInput.takeOrDecode(asset.localPath) else decodeTitleHeader(asset.localPath))
            ?: return@withContext unresolvedWechatScreenshotIdentity().copy(isChatPage = false)
        var prepared: Bitmap? = null
        try {
            val exactBand = titleInput?.hasExactTitleBand == true
            if (exactBand) prepared = prepareWechatTitleHeader(header)
            requireScreenshotBackgroundWork()
            val lines = if (prepared != null) recognizePreparedWechatTitleHeader(prepared, ::recognize)
                else awaitTitleOcrCompletion { recognize(header) }
            val title = selectWechatChatTitleLine(lines, header.width, header.height)?.let {
                if (exactBand) restoreWechatTitleEllipsis(header, it) else it
            }
            // 清洗后无标题时，原始导航只用于判断页面，不把受控件污染的文字拿来确认姓名。
            requireScreenshotBackgroundWork()
            val pageLines = if (exactBand) awaitTitleOcrCompletion { recognize(header) } else lines
            if (exactBand && isWechatNonChatHeader(header, pageLines)) {
                return@withContext unresolvedWechatScreenshotIdentity(title?.text).copy(isChatPage = false)
            }
            val evidence = title?.let { wechatScreenshotTitleEvidence(header, it, exactBand) }
            val visualKey = evidence?.let {
                if (exactBand) wechatNicknamePixelSignature(header, it) else wechatTitlePixelSignature(header, it)
            }
            val observedTitle = evidence?.text ?: title?.text
            val nowMillis = SystemClock.elapsedRealtime()
            val identity = if (keepCurrent == null) {
                titleStabilizer.observe(observedTitle, visualKey, nowMillis, expectedVersion)
            } else {
                observeSnapshotTitle(stabilizer, titleStabilizer, expectedVersion, keepCurrent, observedTitle, visualKey, nowMillis)
            }
            identity.copy(
                isChatPage = isWechatScreenshotChatPage(if (title != null) lines else pageLines, header.width, header.height),
                exactTitleHash = evidence?.let { exactPixelHash(header, IntRect(it.left, it.top, it.right, it.bottom)) },
            )
        } finally {
            prepared?.recycle()
            header.recycle()
        }
    }

    private suspend fun recognize(bitmap: Bitmap): List<OcrTextLine> = suspendCancellableCoroutine { continuation ->
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                if (continuation.isActive) continuation.resume(result.textBlocks.flatMap { block ->
                    block.lines.mapNotNull { line ->
                        line.boundingBox?.let { bounds ->
                            OcrTextLine(line.text, bounds.left, bounds.top, bounds.right, bounds.bottom,
                                line.elements.flatMap { it.symbols }.mapNotNull { symbol ->
                                    symbol.boundingBox?.let { box ->
                                        OcrTextSymbol(symbol.text, box.left, box.top, box.right, box.bottom)
                                    }
                                })
                        }
                    }
                })
            }
            .addOnFailureListener { if (continuation.isActive) continuation.resume(emptyList()) }
            .addOnCanceledListener { if (continuation.isActive) continuation.resume(emptyList()) }
    }

    override fun close() = recognizer.close()
}
