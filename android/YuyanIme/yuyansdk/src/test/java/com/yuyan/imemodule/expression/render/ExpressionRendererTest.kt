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
    fun `合成缓存键包含模板版本`() {
        val renderer = ExpressionRenderer(temporaryFolder.root)

        val oldKey = renderer.cacheKey(asset(version = "v1"), "你好")
        val newKey = renderer.cacheKey(asset(version = "v2"), "你好")

        assertNotEquals(oldKey, newKey)
        assertTrue(newKey.contains("v2"))
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

    private fun asset(version: String) = ExpressionAsset(
        id = "tpl",
        type = "template",
        format = "gif",
        version = version,
        fileName = "templates/tpl.gif",
        sha256 = "a".repeat(64),
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
