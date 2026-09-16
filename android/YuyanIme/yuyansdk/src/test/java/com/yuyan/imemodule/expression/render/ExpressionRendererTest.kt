package com.yuyan.imemodule.expression.render

import android.graphics.Bitmap
import com.bumptech.glide.gifdecoder.GifDecoder
import com.bumptech.glide.gifdecoder.GifHeaderParser
import com.bumptech.glide.gifdecoder.StandardGifDecoder
import com.yuyan.imemodule.expression.model.ExpressionAsset
import com.yuyan.imemodule.expression.model.ExpressionTextLayout
import com.yuyan.imemodule.expression.model.ExpressionTextSafeArea
import java.io.File
import java.nio.ByteBuffer
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
class ExpressionRendererTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `实际内置模板推荐点击后贴完整原句并交付真实成品文件`() = runBlocking {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val phrase = "谢谢你今天帮忙"
        val catalog = com.yuyan.imemodule.expression.ExpressionCatalog.fromAssets(context)
        val template = catalog.synthesisTemplates(phrase).first { it.id == "blank-cat-side-eye" }
        val source = temporaryFolder.newFile("actual-source.gif").apply {
            context.assets.open("expression/${template.fileName}").use { writeBytes(it.readBytes()) }
        }
        val resolver = com.yuyan.imemodule.expression.ExpressionRecommendationResolver(temporaryFolder.root) { source }
        val candidate = resolver.resolveRecommendations(listOf(template), phrase).single()
        assertTrue(ExpressionRenderPolicy.shouldOverlayText(candidate, phrase))
        val renderer = ExpressionRenderer(temporaryFolder.root)
        var delivered: File? = null
        val flow = com.yuyan.imemodule.expression.send.ExpressionFlowController(
            com.yuyan.imemodule.expression.send.ExpressionSendController { result ->
                assertEquals("image/gif", result.mimeType)
                assertTrue(result.file.isFile)
                assertNotEquals(source.canonicalPath, result.file.canonicalPath)
                delivered = result.file
                com.yuyan.imemodule.expression.send.ExpressionSendResult.Sent
            },
            prepareAsset = { selected, text ->
                assertEquals(phrase, text)
                com.yuyan.imemodule.expression.send.PreparedExpression(renderer.render(selected, source, text), "image/gif")
            },
            prepareCombination = { error("not emoji") },
        )
        assertEquals(com.yuyan.imemodule.expression.send.ExpressionSendResult.Sent, flow.prepareAndSend(candidate, phrase))
        val output = requireNotNull(delivered)
        val original = decode(source)
        val composed = decode(output)
        assertEquals(original.frames.size, composed.frames.size)
        assertEquals(original.delays, composed.delays)
        val safe = requireNotNull(template.textSafeArea)
        val calculated = TextLayoutCalculator().calculate(phrase,
            android.graphics.Rect(safe.x, safe.y, safe.x + safe.width, safe.y + safe.height), requireNotNull(template.layout))
        assertEquals(phrase, calculated.lines.joinToString("") { it.text })
        val first = composed.frames.first()
        val old = original.frames.first()
        var changes = 0
        for (y in safe.y until safe.y + safe.height) for (x in safe.x until safe.x + safe.width) {
            if (first.getPixel(x, y) != old.getPixel(x, y)) changes++
        }
        assertTrue("文字区域必须实际改变像素", changes > 100)
        val proof = File(System.getProperty("user.dir"), "build/diy-proof").apply { mkdirs() }
        output.copyTo(File(proof, "diy-thanks.gif"), overwrite = true)
        File(proof, "diy-thanks.png").outputStream().use { first.compress(Bitmap.CompressFormat.PNG, 100, it) }
        (original.frames + composed.frames).forEach { it.recycle() }
    }

    @Test
    fun `合成缓存键包含模板版本`() {
        val renderer = ExpressionRenderer(temporaryFolder.root)

        val oldKey = renderer.cacheKey(asset(version = "v1"), "你好")
        val newKey = renderer.cacheKey(asset(version = "v2"), "你好")

        assertNotEquals(oldKey, newKey)
        assertTrue(newKey.contains("v2"))
    }

    @Test
    fun `合成缓存键包含素材哈希`() {
        val renderer = ExpressionRenderer(temporaryFolder.root)

        val oldKey = renderer.cacheKey(asset(version = "v1", sha256 = "a".repeat(64)), "你好")
        val newKey = renderer.cacheKey(asset(version = "v1", sha256 = "b".repeat(64)), "你好")

        assertNotEquals(oldKey, newKey)
    }

    @Test
    fun `两帧 GIF 合成后保留帧数延迟透明度且每帧出现文字像素`() = runBlocking {
        val source = temporaryFolder.newFile("source.gif").apply { writeBytes(TWO_FRAME_GIF) }
        val target = temporaryFolder.newFile("rendered.gif")
        val renderer = GifTemplateRenderer()

        renderer.render(
            source = source,
            target = target,
            text = "OK",
            safeArea = ExpressionTextSafeArea(2, 2, 28, 28),
            layout = ExpressionTextLayout(
                minFontSize = 8,
                maxFontSize = 14,
                textColor = "#ffffff",
                strokeColor = "#000000",
                strokeWidth = 1,
                alignment = "center",
                maxLines = 2,
            ),
        )

        val original = decode(source)
        val rendered = decode(target)
        assertEquals(2, rendered.frames.size)
        assertEquals(original.delays, rendered.delays)
        rendered.frames.forEach { frame ->
            val pixels = IntArray(frame.width * frame.height)
            frame.getPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)
            assertTrue(pixels.any { android.graphics.Color.alpha(it) == 0 })
            assertTrue(pixels.any { android.graphics.Color.alpha(it) > 0 && android.graphics.Color.red(it) > 220 })
        }
    }

    private fun decode(file: File): DecodedGif {
        val bytes = file.readBytes()
        val header = GifHeaderParser().setData(bytes).parseHeader()
        val decoder = StandardGifDecoder(BitmapProvider, header, ByteBuffer.wrap(bytes))
        val frames = buildList {
            repeat(decoder.frameCount) {
                decoder.advance()
                add(requireNotNull(decoder.nextFrame))
            }
        }
        return DecodedGif(frames, List(decoder.frameCount, decoder::getDelay))
    }

    private fun asset(version: String, sha256: String = "a".repeat(64)) = ExpressionAsset(
        id = "tpl",
        type = "template",
        format = "gif",
        version = version,
        fileName = "templates/tpl.gif",
        sha256 = sha256,
        width = 32,
        height = 32,
    )

    private data class DecodedGif(val frames: List<Bitmap>, val delays: List<Int>)

    private object BitmapProvider : GifDecoder.BitmapProvider {
        override fun obtain(width: Int, height: Int, config: Bitmap.Config): Bitmap =
            Bitmap.createBitmap(width, height, config)

        override fun release(bitmap: Bitmap) = bitmap.recycle()
        override fun obtainByteArray(size: Int): ByteArray = ByteArray(size)
        override fun release(bytes: ByteArray) = Unit
        override fun obtainIntArray(size: Int): IntArray = IntArray(size)
        override fun release(array: IntArray) = Unit
    }

    companion object {
        private val TWO_FRAME_GIF = Base64.getDecoder().decode(
            "R0lGODlhIAAgAIEAAAAAAP8AAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQJCAAAACwAAAAAIAAgAAAIUQABCBxIsKDBgwgTKlzIsKHDhxAjSpxIsaLFggEyatwYoCLHjx4/bgwpMiPJkidFpgRJsaTGlRxhjmzpsiNNlzJfXtzJs6fPn0CDCh1KtOjPgAAh+QQJDAAAACwQAAgACwANAIEAAAAAAP8AAAAAAAAIFgADCBxIsKDBgwgTKlzIsKHDhxAPBgQAOw==",
        )
    }
}
