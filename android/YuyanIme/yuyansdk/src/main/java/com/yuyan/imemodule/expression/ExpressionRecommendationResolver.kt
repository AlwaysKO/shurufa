package com.yuyan.imemodule.expression

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.yuyan.imemodule.expression.model.ExpressionAsset
import com.yuyan.imemodule.expression.render.ExpressionRenderPolicy
import com.yuyan.imemodule.expression.render.StaticTemplateRenderer
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ExpressionRecommendationResolver(
    cacheDir: File,
    private val renderer: StaticTemplateRenderer = StaticTemplateRenderer(),
    private val resolveSource: suspend (ExpressionAsset) -> File,
) {
    private val previewCache = File(cacheDir, "expression-previews")

    fun cacheKey(asset: ExpressionAsset, query: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${asset.sha256}\u0000$query".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "${asset.id}-$digest"
    }

    suspend fun resolve(
        assets: List<ExpressionAsset>,
        query: String,
    ): List<ExpressionAsset> = withContext(Dispatchers.IO) {
        assets.mapNotNull { asset ->
            when {
                asset.type == "prebuilt" && (asset.url != null || asset.thumbnailUrl != null) ->
                    runCatching {
                        asset.copy(thumbnailUrl = Uri.fromFile(resolveSource(asset)).toString())
                    }.getOrNull()
                asset.type == "prebuilt" -> asset
                ExpressionRenderPolicy.shouldOverlayText(asset, query) -> runCatching {
                    asset.copy(thumbnailUrl = Uri.fromFile(renderPreview(asset, query)).toString())
                }.getOrNull()
                else -> null
            }
        }
    }

    private suspend fun renderPreview(asset: ExpressionAsset, query: String): File {
        previewCache.mkdirs()
        val target = File(previewCache, "${cacheKey(asset, query)}.webp")
        if (target.isFile) return target
        val source = resolveSource(asset)
        val safeArea = requireNotNull(asset.textSafeArea)
        val layout = requireNotNull(asset.layout)
        val bitmap = requireNotNull(BitmapFactory.decodeFile(source.path)) {
            "failed to decode expression preview"
        }
        val rendered = renderer.render(bitmap, query, safeArea, layout)
        val part = File(target.parentFile, "${target.name}.part")
        try {
            part.outputStream().use { output ->
                check(rendered.compress(Bitmap.CompressFormat.WEBP, 100, output)) {
                    "failed to encode expression preview"
                }
            }
            if (target.exists()) target.delete()
            check(part.renameTo(target)) { "failed to cache expression preview" }
            return target
        } finally {
            bitmap.recycle()
            rendered.recycle()
            part.delete()
        }
    }
}
