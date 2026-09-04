package com.yuyan.imemodule.expression

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import com.bumptech.glide.gifdecoder.GifHeaderParser
import com.yuyan.imemodule.expression.model.ExpressionAsset
import com.yuyan.imemodule.expression.model.ExpressionTextLayout
import com.yuyan.imemodule.expression.model.ExpressionTextSafeArea
import com.yuyan.imemodule.expression.ui.previewSource
import java.io.File
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExpressionRecommendationResolverTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `预制图直接使用缩略图而合成模板生成带完整查询的本地预览`() = runBlocking {
        val source = temporaryFolder.newFile("source.png")
        Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(Color.BLUE)
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        var sourceCalls = 0
        val resolver = ExpressionRecommendationResolver(temporaryFolder.root) {
            sourceCalls += 1
            source
        }
        val prebuilt = asset("prebuilt", "prebuilt").copy(
            format = "gif",
            fileName = "prebuilt/prebuilt.gif",
            thumbnailFileName = "thumbnails/prebuilt.webp",
        )
        val templates = listOf(asset("one", "synthesis-template"), asset("two", "synthesis-template"))
        val query = "生僻完整词"

        val resolved = resolver.resolve(listOf(prebuilt, *templates.toTypedArray()), query)

        assertEquals(
            "file:///android_asset/expression/prebuilt/prebuilt.gif",
            previewSource(resolved.first()),
        )
        assertEquals(2, sourceCalls)
        val rendered = resolved.drop(1)
        rendered.forEach { item ->
            val expected = File(
                temporaryFolder.root,
                "expression-previews/${resolver.cacheKey(item, query)}.webp",
            )
            assertTrue(expected.isFile)
            assertEquals(Uri.fromFile(expected).toString(), item.thumbnailUrl)
        }
        assertNotEquals(resolver.cacheKey(templates.first(), query), resolver.cacheKey(templates.first(), "生僻"))
    }

    @Test
    fun `动态合成模板生成带文字的本地多帧 GIF 预览`() = runBlocking {
        val source = temporaryFolder.newFile("source.gif").apply { writeBytes(TWO_FRAME_GIF) }
        val resolver = ExpressionRecommendationResolver(temporaryFolder.root) { source }
        val gif = asset("animated", "synthesis-template").copy(
            format = "gif",
            fileName = "templates/animated.gif",
            width = 32,
            height = 32,
            textSafeArea = ExpressionTextSafeArea(2, 2, 28, 28),
        )
        val query = "谢谢"

        val resolved = resolver.resolve(listOf(gif), query).single()
        val expected = File(
            temporaryFolder.root,
            "expression-previews/${resolver.cacheKey(gif, query)}.gif",
        )

        assertTrue(expected.isFile)
        assertEquals(Uri.fromFile(expected).toString(), resolved.thumbnailUrl)
        assertEquals(Uri.fromFile(expected).toString(), previewSource(resolved))
        assertEquals(2, GifHeaderParser().setData(expected.readBytes()).parseHeader().numFrames)
        assertNotEquals(resolver.cacheKey(gif, query), resolver.cacheKey(gif, "再见"))
    }

    @Test
    fun `远端 GIF 先通过鉴权下载再使用本地动态文件预览`() = runBlocking {
        val downloaded = temporaryFolder.newFile("downloaded.gif").apply { writeText("image") }
        var resolvedAsset: ExpressionAsset? = null
        val resolver = ExpressionRecommendationResolver(temporaryFolder.root) { asset ->
            resolvedAsset = asset
            downloaded
        }
        val remote = asset("remote", "prebuilt").copy(
            format = "gif",
            fileName = "prebuilt/remote.gif",
            url = "/uploads/expression/prebuilt/remote.gif",
            thumbnailUrl = "/uploads/expression/thumbnails/remote.webp",
        )

        val resolved = resolver.resolve(listOf(remote), "你好").single()

        assertEquals(remote, resolvedAsset)
        assertEquals(Uri.fromFile(downloaded).toString(), resolved.thumbnailUrl)
        assertEquals(Uri.fromFile(downloaded).toString(), previewSource(resolved))
    }

    private fun asset(id: String, type: String) = ExpressionAsset(
        id = id,
        type = type,
        format = "webp",
        version = "v1",
        fileName = "templates/$id.webp",
        thumbnailFileName = "thumbnails/$id.webp",
        sha256 = id.padEnd(64, 'a').take(64),
        width = 128,
        height = 128,
        textSafeArea = ExpressionTextSafeArea(8, 8, 112, 48),
        layout = ExpressionTextLayout(
            minFontSize = 12,
            maxFontSize = 24,
            textColor = "#ffffff",
            strokeColor = "#000000",
            strokeWidth = 1,
            alignment = "center",
            maxLines = 2,
        ),
    )

    companion object {
        private val TWO_FRAME_GIF = Base64.getDecoder().decode(
            "R0lGODlhIAAgAIEAAAAAAP8AAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQJCAAAACwAAAAAIAAgAAAIUQABCBxIsKDBgwgTKlzIsKHDhxAjSpxIsaLFggEyatwYoCLHjx4/bgwpMiPJkidFpgRJsaTGlRxhjmzpsiNNlzJfXtzJs6fPn0CDCh1KtOjPgAAh+QQJDAAAACwQAAgACwANAIEAAAAAAP8AAAAAAAAIFgADCBxIsKDBgwgTKlzIsKHDhxAPBgQAOw==",
        )
    }
}
