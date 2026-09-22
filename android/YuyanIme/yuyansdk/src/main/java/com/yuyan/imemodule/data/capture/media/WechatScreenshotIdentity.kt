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
    val raw = recognizedTitle?.trim()?.replace(Regex("\\s+"), " ").orEmpty()
    // 荣耀截图中微信群人数偶尔被 OCR 拆成“(6)8”；尾部孤立数字同群人数一起丢弃。
    val groupSuffix = Regex("[（(]\\s*\\d+\\s*[）)](?:\\s*[A-Za-z0-9]{1,2})?$")
    val isGroup = groupSuffix.containsMatchIn(raw)
    val normalized = raw.replace(groupSuffix, "").trim().takeIf { it.isNotEmpty() }
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

internal interface ScreenshotConversationIdentityResolver {
    fun version(): Long = 0L
    suspend fun resolve(asset: PendingAssetEntity, expectedVersion: Long = version(), titleInput: TitleOcrInput? = null): ScreenshotConversationIdentity
    fun reset() = Unit
}

internal class MlKitWechatScreenshotIdentityResolver(identityStore: ConversationIdentityStore = MemoryConversationIdentityStore()) : ScreenshotConversationIdentityResolver, Closeable {
    private val stabilizer = WechatTitleStabilizer(identityStore)
    override fun reset() = stabilizer.reset()
    override fun version(): Long = stabilizer.version()

    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    override suspend fun resolve(asset: PendingAssetEntity, expectedVersion: Long, titleInput: TitleOcrInput?): ScreenshotConversationIdentity = withContext(Dispatchers.Default) {
        val header = (if (titleInput != null) titleInput.takeOrDecode(asset.localPath) else decodeTitleHeader(asset.localPath))
            ?: return@withContext unresolvedWechatScreenshotIdentity().copy(isChatPage = false)
        var prepared: Bitmap? = null
        try {
            val exactBand = titleInput?.hasExactTitleBand == true
            if (exactBand) prepared = prepareWechatTitleHeader(header)
            val lines = awaitTitleOcrCompletion { recognize(prepared ?: header) }
            val title = selectWechatChatTitleLine(lines, header.width, header.height)?.let {
                if (exactBand) restoreWechatTitleEllipsis(header, it) else it
            }
            // 清洗后无标题时，原始导航只用于判断页面，不把受控件污染的文字拿来确认姓名。
            val pageLines = if (prepared != null && title == null)
                awaitTitleOcrCompletion { recognize(header) } else lines
            val evidence = title?.let { if (exactBand) wechatTitleEvidenceBounds(header, it) else it }
            val visualKey = evidence?.let {
                if (exactBand) wechatNicknamePixelSignature(header, it) else wechatTitlePixelSignature(header, it)
            }
            stabilizer.observe(
                title = title?.text,
                visualKey = visualKey,
                nowMillis = SystemClock.elapsedRealtime(),
                expectedVersion = expectedVersion,
            ).copy(
                isChatPage = isWechatScreenshotChatPage(pageLines, header.width, header.height),
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
