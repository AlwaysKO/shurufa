package com.yuyan.imemodule.expression.send

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import androidx.core.content.FileProvider
import com.yuyan.imemodule.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ExpressionContentSender(
    private val context: Context,
    private val inputConnection: () -> InputConnection?,
    private val editorMimeTypes: () -> Array<String>?,
) : ExpressionSender {
    override suspend fun send(expression: PreparedExpression): ExpressionSendResult =
        withContext(Dispatchers.Main.immediate) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) {
                return@withContext ExpressionSendResult.UnsupportedTarget
            }
            val connection = inputConnection() ?: return@withContext ExpressionSendResult.UnsupportedTarget
            val supported = editorMimeTypes().orEmpty().any { accepted ->
                accepted == "image/*" || accepted.equals(expression.mimeType, ignoreCase = true)
            }
            if (!supported) return@withContext ExpressionSendResult.UnsupportedTarget
            runCatching {
                val uri = contentUri(expression.file)
                val content = InputContentInfo(
                    uri,
                    ClipDescription(expression.displayName, arrayOf(expression.mimeType)),
                    null,
                )
                if (connection.commitContent(content, INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null)) {
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

        fun mimeOf(format: String): String = when (format.lowercase()) {
            "gif" -> "image/gif"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            else -> "image/png"
        }
    }
}
