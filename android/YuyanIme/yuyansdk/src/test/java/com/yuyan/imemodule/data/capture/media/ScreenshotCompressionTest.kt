package com.yuyan.imemodule.data.capture.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotCompressionTest {
    @Test fun smallTextScreenshotPreservesPixelsAndUsesModerateQuality() {
        val source = textScreenshot()
        try {
            val result = encodeWebp(source)
            assertArrayEquals(webp(source, 78), result)
            assertTrue("must reduce the old quality-82 payload", result.size < webp(source, 82).size)
            val decoded = BitmapFactory.decodeByteArray(result, 0, result.size)
            assertEquals(source.width, decoded.width)
            assertEquals(source.height, decoded.height)
            assertFalse(source.isRecycled)
            decoded.recycle()
            System.getenv("SCREENSHOT_COMPRESSION_ARTIFACTS")?.let { directory ->
                File(directory).mkdirs()
                File(directory, "synthetic-source.png").writeBytes(encodeLossless(source))
                File(directory, "synthetic-quality82.webp").writeBytes(webp(source, 82))
                File(directory, "synthetic-compressed.webp").writeBytes(result)
            }
        } finally { source.recycle() }
    }

    @Test fun largeNoisyScreenshotStopsAtQualityFloorWithoutDownscaling() {
        val source = Bitmap.createBitmap(768, 1024, Bitmap.Config.ARGB_8888)
        val random = Random(33)
        val pixels = IntArray(source.width * source.height) { Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)) }
        source.setPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        val text = textScreenshot()
        Canvas(source).apply {
            save()
            clipRect(0, 0, 540, 220)
            drawBitmap(text, 0f, 0f, null)
            restore()
        }
        text.recycle()
        try {
            assertTrue(webp(source, 78).size > 256 * 1024)
            val result = encodeWebp(source)
            assertArrayEquals(webp(source, 72), result)
            System.getenv("SCREENSHOT_COMPRESSION_ARTIFACTS")?.let { directory ->
                File(directory).mkdirs()
                File(directory, "synthetic-large-source.png").writeBytes(encodeLossless(source))
                File(directory, "synthetic-large-quality82.webp").writeBytes(webp(source, 82))
                File(directory, "synthetic-large-compressed.webp").writeBytes(result)
            }
            assertTrue("soft size goal must not force unreadable quality", result.size > 256 * 1024)
            val decoded = BitmapFactory.decodeByteArray(result, 0, result.size)
            assertEquals(source.width, decoded.width)
            assertEquals(source.height, decoded.height)
            decoded.recycle()
        } finally { source.recycle() }
    }

    private fun textScreenshot(): Bitmap {
        val bitmap = Bitmap.createBitmap(540, 800, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(25, 25, 25) }
        var y = 35f
        for (size in listOf(12f, 14f, 16f, 20f, 24f)) {
            paint.textSize = size
            for (line in listOf("截图压缩测试：中文小字仍需清晰可读。", "Wifi only / ABC xyz 0123456789", "联系人：测试用户；时间 09:35，金额 ¥128.60")) {
                canvas.drawText(line, 16f, y, paint)
                y += 31f
            }
        }
        return bitmap
    }

    @Suppress("DEPRECATION")
    private fun webp(bitmap: Bitmap, quality: Int): ByteArray = ByteArrayOutputStream().use {
        val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        check(bitmap.compress(format, quality, it))
        it.toByteArray()
    }
}
