package com.yuyan.imemodule.data.capture.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
)

internal fun selectWechatChatTitle(
    lines: List<OcrTextLine>,
    imageWidth: Int,
    headerHeight: Int,
): String? = lines.asSequence()
    .map { it to it.text.trim() }
    .filter { (line, text) ->
        text.length in 1..80 && line.top < headerHeight && line.bottom > 0 &&
            abs((line.left + line.right) / 2.0 - imageWidth / 2.0) <= imageWidth * 0.32 &&
            !isWechatHeaderNoise(text)
    }
    .sortedWith(compareBy<Pair<OcrTextLine, String>>(
        { abs((it.first.left + it.first.right) / 2.0 - imageWidth / 2.0) },
        { -((it.first.bottom - it.first.top).coerceAtLeast(0)) },
    ))
    .map { it.second }
    .firstOrNull()

private fun isWechatHeaderNoise(text: String): Boolean =
    text in setOf("微信", "返回", "···", "...", "5G", "4G") ||
        text.matches(Regex("^\\d{1,2}:\\d{2}$")) ||
        text.matches(Regex("^\\d{1,3}%$")) ||
        text.all { it.isDigit() || it in " %:·." }

internal fun screenshotConversationIdentity(
    recognizedTitle: String?,
    fallbackHeaderHash: String,
): ScreenshotConversationIdentity {
    val raw = recognizedTitle?.trim()?.replace(Regex("\\s+"), " ").orEmpty()
    // 荣耀截图中微信群人数偶尔被 OCR 拆成“(6)8”；尾部孤立数字同群人数一起丢弃。
    val groupSuffix = Regex("[（(]\\s*\\d+\\s*[）)](?:\\s*\\d{1,2})?$")
    val isGroup = groupSuffix.containsMatchIn(raw)
    val normalized = raw.replace(groupSuffix, "").trim().takeIf { it.isNotEmpty() }
    if (normalized != null) {
        val stableTitle = Normalizer.normalize(normalized, Normalizer.Form.NFKC).lowercase()
        val key = sha256(stableTitle.toByteArray(Charsets.UTF_8))
        return ScreenshotConversationIdentity(
            externalKey = "title:$key",
            displayName = normalized,
            conversationType = if (isGroup) ConversationType.GROUP else ConversationType.UNKNOWN,
            confidence = 0.9,
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
    suspend fun resolve(asset: PendingAssetEntity): ScreenshotConversationIdentity
}

internal class MlKitWechatScreenshotIdentityResolver : ScreenshotConversationIdentityResolver, Closeable {
    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    override suspend fun resolve(asset: PendingAssetEntity): ScreenshotConversationIdentity = withContext(Dispatchers.Default) {
        val bitmap = BitmapFactory.decodeFile(asset.localPath)
            ?: return@withContext screenshotConversationIdentity(null, asset.perceptualHash.orEmpty())
        val headerHeight = (bitmap.width * 0.18).toInt().coerceIn(96, minOf(220, bitmap.height))
        val header = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, headerHeight)
        bitmap.recycle()
        try {
            val fallbackHash = differenceHash(header)
            val lines = recognize(header)
            screenshotConversationIdentity(
                selectWechatChatTitle(lines, header.width, header.height),
                fallbackHash,
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
