package com.yuyan.imemodule.data.capture.notification

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.yuyan.imemodule.data.capture.db.PendingAssetEntity
import com.yuyan.imemodule.data.capture.media.differenceHash
import com.yuyan.imemodule.data.capture.media.encodeLossless
import com.yuyan.imemodule.data.capture.sha256
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class NotificationMediaImporter(private val context: Context) {
    fun canRead(uri: Uri): Boolean = try {
        context.contentResolver.openInputStream(uri)?.use { true } ?: false
    } catch (_: Exception) {
        false
    }

    suspend fun importImage(uri: Uri): PendingAssetEntity? = withContext(Dispatchers.IO) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            } ?: return@withContext null
            if (bounds.outWidth !in 1..MAX_IMAGE_SIDE || bounds.outHeight !in 1..MAX_IMAGE_SIDE) {
                return@withContext null
            }
            if (bounds.outWidth.toLong() * bounds.outHeight > MAX_IMAGE_PIXELS) return@withContext null

            val bitmap = context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
                ?: return@withContext null
            try {
                val encoded = encodeLossless(bitmap)
                if (encoded.isEmpty() || encoded.size > MAX_ENCODED_BYTES) return@withContext null
                val contentHash = sha256(encoded)
                val output = File(context.cacheDir, "chat-capture/$contentHash")
                if (!output.isFile) {
                    output.parentFile?.mkdirs()
                    val temporary = File(output.parentFile, "$contentHash.${UUID.randomUUID()}.tmp")
                    try {
                        temporary.writeBytes(encoded)
                        if (!temporary.renameTo(output) && !output.isFile) return@withContext null
                    } finally {
                        temporary.delete()
                    }
                }
                PendingAssetEntity(
                    sha256 = contentHash,
                    localPath = output.absolutePath,
                    mimeType = "image/png",
                    perceptualHash = differenceHash(bitmap),
                    width = bitmap.width,
                    height = bitmap.height,
                )
            } finally {
                bitmap.recycle()
            }
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        const val MAX_IMAGE_SIDE = 4096
        const val MAX_IMAGE_PIXELS = 16_000_000L
        const val MAX_ENCODED_BYTES = 5 * 1024 * 1024
    }
}
