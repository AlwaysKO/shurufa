package com.yuyan.imemodule.expression.send

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Looper
import android.util.Log
import android.provider.OpenableColumns
import android.provider.MediaStore
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import com.yuyan.imemodule.R
import com.yuyan.imemodule.BuildConfig
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ExpressionContentSender(
    private val context: Context,
    private val inputConnection: () -> InputConnection?,
    private val editorMimeTypes: () -> Array<String>?,
    private val editorInfo: () -> EditorInfo? = { null },
) : ExpressionSender {
    override suspend fun send(expression: PreparedExpression): ExpressionSendResult {
        val diagnosticId = if (BuildConfig.DEBUG && expression.mimeType == "image/gif") {
            diagnosticSequence.incrementAndGet().also { diagnoseFile(it, expression) }
        } else null
        // 诊断 IO 完成后才获取当前编辑器和连接，不跨挂起点持有旧目标。
        return withContext(Dispatchers.Main.immediate) {
            val currentEditorInfo = editorInfo()
            // 所有目标统一协商真实 MIME；URI 不能作为正文提交来替代图片发送。
            val connection = inputConnection() ?: return@withContext ExpressionSendResult.UnsupportedTarget
            if (!supportsExpressionMimeType(
                    expressionMimeType = expression.mimeType,
                    editorInfo = currentEditorInfo,
                    fallbackMimeTypes = editorMimeTypes,
                )
            ) return@withContext ExpressionSendResult.UnsupportedTarget
            runCatching {
                val uri = contentUri(expression.file)
                val description = ClipDescription(expression.displayName, arrayOf(expression.mimeType))
                diagnosticId?.let {
                    diagnosticLog(it, "target=${currentEditorInfo?.packageName} clipMime=${expression.mimeType}")
                }
                val committed = if (currentEditorInfo != null) {
                    InputConnectionCompat.commitContent(
                        connection,
                        currentEditorInfo,
                        InputContentInfoCompat(uri, description, null),
                        InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
                        null,
                    )
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                    val content = InputContentInfo(
                        uri,
                        description,
                        null,
                    )
                    connection.commitContent(content, INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null)
                } else {
                    false
                }
                diagnosticId?.let { diagnosticLog(it, "committed=$committed") }
                if (committed) {
                    ExpressionSendResult.Sent
                } else {
                    ExpressionSendResult.Failed(context.getString(R.string.expression_input_rejected_image))
                }
            }.getOrElse { error ->
                ExpressionSendResult.Failed(
                    error.message ?: context.getString(R.string.expression_image_send_failed),
                )
            }
        }
    }

    /** 仅 debug GIF 的交付证据；文件名与 URI 哈希化，绝不记录查询文字或异常 message。 */
    private suspend fun diagnoseFile(id: Long, expression: PreparedExpression) = withContext(Dispatchers.IO) {
        try {
            val uri = contentUri(expression.file)
            val source = expression.file.inputStream().use(::fingerprint)
            val delivered = requireNotNull(context.contentResolver.openInputStream(uri)).use(::fingerprint)
            val metadata = context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use "metadata=empty"
                val name = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)).orEmpty()
                val size = cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE))
                // 只保留已知扩展名，不让任意显示名或文件名进入日志。
                val extension = name.substringAfterLast('.', "").lowercase().takeIf { it == "gif" || it == "0" } ?: "other"
                "displayNameSha256=${sha256(name.toByteArray())} displayExtension=$extension providerSize=$size"
            } ?: "metadata=unavailable"
            diagnosticLog(
                id,
                "fileSha256=${source.sha256} fileSize=${source.size} fileMagic=${source.magic} " +
                    "uriSha256=${delivered.sha256} uriSize=${delivered.size} uriMagic=${delivered.magic} " +
                    "uriIdentitySha256=${sha256(uri.toString().toByteArray())} " +
                    "resolverMime=${context.contentResolver.getType(uri)} $metadata " +
                    "ioMainThread=${Looper.myLooper() == Looper.getMainLooper()}",
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            diagnosticLog(id, "diagnosticError=${error.javaClass.simpleName}")
        }
    }

    private data class Fingerprint(val sha256: String, val size: Long, val magic: String)

    private fun fingerprint(input: InputStream): Fingerprint {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        val magic = ArrayList<Byte>(6)
        var size = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
            for (index in 0 until minOf(count, 6 - magic.size)) magic.add(buffer[index])
            size += count
        }
        return Fingerprint(digest.digest().hex(), size, magic.toByteArray().hex())
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    private fun diagnosticLog(id: Long, message: String) {
        // 即使日志设施异常也不能影响实际交付。
        runCatching { Log.d("ExpressionSendDiag", "id=$id $message") }
    }

    suspend fun saveToGallery(expression: PreparedExpression): Boolean = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext false
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, expression.displayName)
                put(MediaStore.Images.Media.MIME_TYPE, expression.mimeType)
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/YuyanExpressions",
                )
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching false
            context.contentResolver.openOutputStream(uri)?.use { output ->
                expression.file.inputStream().use { input -> input.copyTo(output) }
            } ?: return@runCatching false
            true
        }.getOrDefault(false)
    }

    fun copyToClipboard(expression: PreparedExpression): Boolean = runCatching {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            ClipData.newUri(context.contentResolver, expression.displayName, contentUri(expression.file)),
        )
        true
    }.getOrDefault(false)

    private fun contentUri(file: File) = FileProvider.getUriForFile(
        context,
        "${context.packageName}.expression.fileprovider",
        file,
    )

    companion object {
        private const val INPUT_CONTENT_GRANT_READ_URI_PERMISSION = 1
        private val diagnosticSequence = AtomicLong()

        fun mimeOf(format: String): String = when (format.lowercase()) {
            "gif" -> "image/gif"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            else -> "image/png"
        }
    }
}

internal fun supportsExpressionMimeType(
    expressionMimeType: String,
    editorInfo: EditorInfo?,
    sdkInt: Int = Build.VERSION.SDK_INT,
    fallbackMimeTypes: () -> Array<out String>?,
): Boolean = buildList {
    if (editorInfo != null) {
        addAll(EditorInfoCompat.getContentMimeTypes(editorInfo).asList())
    } else if (sdkInt >= Build.VERSION_CODES.N_MR1) {
        addAll(fallbackMimeTypes().orEmpty().asList())
    }
}.distinct().any { accepted ->
    ClipDescription.compareMimeTypes(expressionMimeType, accepted)
}
