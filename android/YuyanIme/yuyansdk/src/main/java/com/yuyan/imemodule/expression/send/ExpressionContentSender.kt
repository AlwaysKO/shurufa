package com.yuyan.imemodule.expression.send

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import com.yuyan.imemodule.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ExpressionContentSender(
    private val context: Context,
    private val inputConnection: () -> InputConnection?,
    private val editorMimeTypes: () -> Array<String>?,
    private val editorInfo: () -> EditorInfo? = { null },
) : ExpressionSender {
    override suspend fun send(expression: PreparedExpression): ExpressionSendResult =
        withContext(Dispatchers.Main.immediate) {
            val connection = inputConnection() ?: return@withContext ExpressionSendResult.UnsupportedTarget
            val currentEditorInfo = editorInfo()
            if (!supportsExpressionMimeType(
                    expressionMimeType = expression.mimeType,
                    editorInfo = currentEditorInfo,
                    fallbackMimeTypes = editorMimeTypes,
                )
            ) return@withContext ExpressionSendResult.UnsupportedTarget
            runCatching {
                val uri = contentUri(expression.file)
                val description = ClipDescription(expression.displayName, arrayOf(expression.mimeType))
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
