package com.yuyan.imemodule.expression.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.yuyan.imemodule.expression.model.ExpressionAsset
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ExpressionRenderer(
    cacheDir: File,
    private val staticRenderer: StaticTemplateRenderer = StaticTemplateRenderer(),
    private val gifRenderer: GifTemplateRenderer = GifTemplateRenderer(staticRenderer),
) {
    private val composedCache = File(cacheDir, "expression-composed")

    fun cacheKey(asset: ExpressionAsset, text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${asset.id}\u0000${asset.version}\u0000${asset.sha256}\u0000$text".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "${asset.id}-${asset.version}-$digest"
    }

    suspend fun render(asset: ExpressionAsset, source: File, text: String): File {
        val safeArea = requireNotNull(asset.textSafeArea) { "template has no text safe area" }
        val layout = requireNotNull(asset.layout) { "template has no text layout" }
        composedCache.mkdirs()
        val extension = if (asset.format.equals("gif", ignoreCase = true)) "gif" else "webp"
        val target = File(composedCache, "${cacheKey(asset, text)}.$extension")
        if (target.isFile) return target
        return if (extension == "gif") {
            gifRenderer.render(source, target, text, safeArea, layout)
        } else {
            withContext(Dispatchers.Default) {
                val bitmap = requireNotNull(BitmapFactory.decodeFile(source.path)) { "failed to decode template" }
                require(bitmap.width.toLong() * bitmap.height <= MAX_STATIC_PIXELS) { "template pixel count exceeds limit" }
                val rendered = staticRenderer.render(bitmap, text, safeArea, layout)
                val part = File(target.parentFile, "${target.name}.part")
                try {
                    part.outputStream().use { output ->
                        check(rendered.compress(Bitmap.CompressFormat.WEBP, 100, output)) {
                            "failed to encode composed template"
                        }
                    }
                    if (target.exists()) target.delete()
                    check(part.renameTo(target)) { "failed to move composed template into cache" }
                    target
                } finally {
                    bitmap.recycle()
                    rendered.recycle()
                    part.delete()
                }
            }
        }
    }

    companion object {
        private const val MAX_STATIC_PIXELS = 16_000_000L
    }
}
