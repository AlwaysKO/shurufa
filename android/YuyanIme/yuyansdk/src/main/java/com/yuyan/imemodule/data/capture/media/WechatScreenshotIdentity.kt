package com.yuyan.imemodule.data.capture.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.yuyan.imemodule.data.capture.db.PendingAssetEntity
import com.yuyan.imemodule.data.capture.model.ConversationType
import com.yuyan.imemodule.data.capture.sha256
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
)

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
            !isWechatHeaderNoise(text)
    }
    .sortedWith(compareBy<Pair<OcrTextLine, String>>(
        { abs((it.first.left + it.first.right) / 2.0 - imageWidth / 2.0) },
        { -((it.first.bottom - it.first.top).coerceAtLeast(0)) },
    ))
    .map { it.first }
    .firstOrNull()

private fun isWechatHeaderNoise(text: String): Boolean =
    text in setOf("微信", "返回", "···", "...", "5G", "4G", "く", "〈", "〉", "<", ">", "‹", "›", "←", "→", "×") ||
        text.matches(Regex("^\\d{1,2}:\\d{2}$")) ||
        text.matches(Regex("^\\d{1,3}%$")) ||
        text.all { it.isDigit() || it in " %:·." }

/** 首次立即探测也可能仍停留在列表；无聊天页证据时只重试，不保存成待确认截图。 */
internal fun isWechatScreenshotChatPage(lines: List<OcrTextLine>, width: Int, headerHeight: Int): Boolean {
    val nonChatTitles = setOf("微信", "通讯录", "发现", "我", "搜索", "设置", "聊天信息", "朋友圈", "新的朋友", "群聊")
    val header = lines.filter { it.top >= headerHeight * 0.18 && it.bottom <= headerHeight }
    if (header.any { it.text.trim() in nonChatTitles && it.top < headerHeight * 0.65 &&
            abs((it.left + it.right) / 2.0 - width / 2.0) < width * 0.3 }) return false
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
    suspend fun resolve(asset: PendingAssetEntity, expectedVersion: Long = version()): ScreenshotConversationIdentity
    fun reset() = Unit
}

internal class MlKitWechatScreenshotIdentityResolver(identityStore: ConversationIdentityStore = MemoryConversationIdentityStore()) : ScreenshotConversationIdentityResolver, Closeable {
    private val stabilizer = WechatTitleStabilizer(identityStore)
    override fun reset() = stabilizer.reset()
    override fun version(): Long = stabilizer.version()

    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    override suspend fun resolve(asset: PendingAssetEntity, expectedVersion: Long): ScreenshotConversationIdentity = withContext(Dispatchers.Default) {
        val bitmap = BitmapFactory.decodeFile(asset.localPath)
            ?: return@withContext unresolvedWechatScreenshotIdentity().copy(isChatPage = false)
        val headerHeight = (bitmap.width * 0.18).toInt().coerceAtLeast(96).coerceAtMost(minOf(220, bitmap.height))
        val header = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, headerHeight)
        if (bitmap !== header) bitmap.recycle()
        try {
            val lines = recognize(header)
            if (!isWechatScreenshotChatPage(lines, header.width, header.height)) {
                return@withContext unresolvedWechatScreenshotIdentity().copy(isChatPage = false)
            }
            val title = selectWechatChatTitleLine(lines, header.width, header.height)
            stabilizer.observe(
                title = title?.text,
                visualKey = title?.let { wechatTitlePixelSignature(header, it) },
                nowMillis = SystemClock.elapsedRealtime(),
                expectedVersion = expectedVersion,
            )
        } finally {
            header.recycle()
        }
    }

    private suspend fun recognize(bitmap: Bitmap): List<OcrTextLine> = suspendCancellableCoroutine { continuation ->
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                if (continuation.isActive) continuation.resume(result.textBlocks.flatMap { block ->
                    block.lines.mapNotNull { line ->
                        line.boundingBox?.let { bounds ->
                            OcrTextLine(line.text, bounds.left, bounds.top, bounds.right, bounds.bottom)
                        }
                    }
                })
            }
            .addOnFailureListener { if (continuation.isActive) continuation.resume(emptyList()) }
            .addOnCanceledListener { if (continuation.isActive) continuation.resume(emptyList()) }
    }

    override fun close() = recognizer.close()
}
