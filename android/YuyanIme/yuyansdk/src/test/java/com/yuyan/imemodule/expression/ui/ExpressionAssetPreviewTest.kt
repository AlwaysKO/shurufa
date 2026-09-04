package com.yuyan.imemodule.expression.ui

import com.yuyan.imemodule.data.collect.ServerConfig
import com.yuyan.imemodule.expression.model.ExpressionAsset
import org.junit.Assert.assertEquals
import org.junit.Test

class ExpressionAssetPreviewTest {
    @Test
    fun `内置 GIF 忽略静态缩略图并播放原动画`() {
        val asset = asset(format = "gif").copy(
            fileName = "templates/reaction.gif",
            thumbnailFileName = "thumbnails/reaction.webp",
        )

        assertEquals(
            "file:///android_asset/expression/templates/reaction.gif",
            previewSource(asset),
        )
    }

    @Test
    fun `远端 GIF 忽略远端缩略图并播放原动画`() {
        val asset = asset(format = "gif").copy(
            url = "/uploads/expression/reaction.gif",
            thumbnailUrl = "/uploads/expression/thumbnails/reaction.webp",
        )

        assertEquals(
            "${ServerConfig.baseUrl}/uploads/expression/reaction.gif",
            previewSource(asset),
        )
    }

    @Test
    fun `已下载 GIF 优先播放本地动态文件`() {
        val asset = asset(format = "gif").copy(
            url = "https://example.test/reaction.gif",
            thumbnailUrl = "file:///cache/expression/downloaded.GIF",
        )

        assertEquals("file:///cache/expression/downloaded.GIF", previewSource(asset))
    }

    @Test
    fun `合成 GIF 跳过本地静态预览并播放内置原动画`() {
        val asset = asset(format = "gif").copy(
            type = "synthesis-template",
            fileName = "templates/reaction.gif",
            thumbnailUrl = "file:///cache/expression-previews/reaction.webp",
        )

        assertEquals(
            "file:///android_asset/expression/templates/reaction.gif",
            previewSource(asset),
        )
    }

    @Test
    fun `静态 WebP 继续优先使用缩略图`() {
        val asset = asset(format = "webp").copy(
            fileName = "prebuilt/reaction.webp",
            thumbnailFileName = "thumbnails/reaction.webp",
            url = "https://example.test/reaction.webp",
            thumbnailUrl = "https://example.test/reaction-thumb.webp",
        )

        assertEquals("https://example.test/reaction-thumb.webp", previewSource(asset))
    }

    private fun asset(format: String) = ExpressionAsset(
        id = "reaction",
        type = "prebuilt",
        format = format,
        version = "v1",
        fileName = "prebuilt/reaction.$format",
        sha256 = "a".repeat(64),
        width = 240,
        height = 240,
    )
}
