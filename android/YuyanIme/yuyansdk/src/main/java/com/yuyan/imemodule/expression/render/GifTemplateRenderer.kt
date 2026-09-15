package com.yuyan.imemodule.expression.render

import android.graphics.Bitmap
import com.bumptech.glide.gifdecoder.GifDecoder
import com.bumptech.glide.gifdecoder.GifHeaderParser
import com.bumptech.glide.gifdecoder.StandardGifDecoder
import com.squareup.gifencoder.DisposalMethod
import com.squareup.gifencoder.GifEncoder
import com.squareup.gifencoder.ImageOptions
import com.yuyan.imemodule.expression.model.ExpressionTextLayout
import com.yuyan.imemodule.expression.model.ExpressionTextSafeArea
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class GifTemplateRenderer(
    private val staticRenderer: StaticTemplateRenderer = StaticTemplateRenderer(),
    private val maxFrames: Int = 120,
    private val maxTotalPixels: Long = 24_000_000,
) {
    suspend fun render(
        source: File,
        target: File,
        text: String,
        safeArea: ExpressionTextSafeArea,
        layout: ExpressionTextLayout,
    ): File = withContext(Dispatchers.Default) {
        val part = File(target.parentFile, "${target.name}.part")
        try {
            val bytes = source.readBytes()
            val header = GifHeaderParser().setData(bytes).parseHeader()
            require(header.numFrames in 1..maxFrames) { "GIF frame count exceeds limit" }
            require(header.width.toLong() * header.height * header.numFrames <= maxTotalPixels) {
                "GIF pixel count exceeds limit"
            }
            val decoder = StandardGifDecoder(BitmapProvider, header, ByteBuffer.wrap(bytes)).apply {
                setDefaultBitmapConfig(Bitmap.Config.ARGB_8888)
            }
            val encoded = ByteArrayOutputStream()
            val encoder = GifEncoder(encoded, header.width, header.height, 0)
            val transparentFrames = ArrayList<Boolean>(header.numFrames)
            repeat(header.numFrames) {
                coroutineContext.ensureActive()
                decoder.advance()
                val frame = requireNotNull(decoder.nextFrame) { "failed to decode GIF frame $it" }
                val rendered = staticRenderer.render(frame, text, safeArea, layout)
                val pixels = IntArray(rendered.width * rendered.height)
                rendered.getPixels(pixels, 0, rendered.width, 0, 0, rendered.width, rendered.height)
                var hasTransparency = false
                pixels.indices.forEach { index ->
                    val color = pixels[index]
                    if (android.graphics.Color.alpha(color) < 128) {
                        pixels[index] = TRANSPARENT_RGB
                        hasTransparency = true
                    } else {
                        pixels[index] = color and 0x00ffffff
                    }
                }
                transparentFrames += hasTransparency
                encoder.addImage(
                    pixels,
                    rendered.width,
                    ImageOptions()
                        .setDelay(decoder.getDelay(it).toLong(), TimeUnit.MILLISECONDS)
                        .setDisposalMethod(DisposalMethod.DO_NOT_DISPOSE),
                )
                frame.recycle()
                rendered.recycle()
            }
            encoder.finishEncoding()
            decoder.clear()
            val output = applyTransparency(encoded.toByteArray(), transparentFrames)
            part.parentFile?.mkdirs()
            part.writeBytes(output)
            coroutineContext.ensureActive()
            if (target.exists()) target.delete()
            check(part.renameTo(target)) { "failed to move composed GIF into cache" }
            target
        } finally {
            part.delete()
        }
    }

    private fun applyTransparency(bytes: ByteArray, transparentFrames: List<Boolean>): ByteArray {
        if (bytes.size < 13) return bytes
        var offset = 13
        var globalPaletteOffset = -1
        var globalPaletteSize = 0
        val logicalPacked = bytes[10].toInt() and 0xff
        if (logicalPacked and 0x80 != 0) {
            globalPaletteOffset = offset
            globalPaletteSize = 1 shl ((logicalPacked and 0x07) + 1)
            offset += globalPaletteSize * 3
        }
        var pendingGraphicControl = -1
        var frameIndex = 0
        while (offset < bytes.size) {
            when (bytes[offset].toInt() and 0xff) {
                0x21 -> {
                    if (offset + 2 >= bytes.size) return bytes
                    val label = bytes[offset + 1].toInt() and 0xff
                    if (label == 0xf9 && offset + 7 < bytes.size) {
                        pendingGraphicControl = offset
                        offset += 8
                    } else {
                        offset += 2
                        offset = skipSubBlocks(bytes, offset)
                    }
                }
                0x2c -> {
                    if (offset + 9 >= bytes.size) return bytes
                    val packed = bytes[offset + 9].toInt() and 0xff
                    offset += 10
                    val localPaletteSize = if (packed and 0x80 != 0) 1 shl ((packed and 0x07) + 1) else 0
                    val paletteOffset = if (localPaletteSize > 0) offset else globalPaletteOffset
                    val paletteSize = if (localPaletteSize > 0) localPaletteSize else globalPaletteSize
                    if (frameIndex < transparentFrames.size && transparentFrames[frameIndex] && pendingGraphicControl >= 0) {
                        val transparentIndex = nearestPaletteIndex(bytes, paletteOffset, paletteSize, TRANSPARENT_RGB)
                        bytes[pendingGraphicControl + 3] = (bytes[pendingGraphicControl + 3].toInt() or 0x01).toByte()
                        bytes[pendingGraphicControl + 6] = transparentIndex.toByte()
                    }
                    offset += localPaletteSize * 3
                    if (offset >= bytes.size) return bytes
                    offset += 1
                    offset = skipSubBlocks(bytes, offset)
                    pendingGraphicControl = -1
                    frameIndex += 1
                }
                0x3b -> return bytes
                else -> return bytes
            }
        }
        return bytes
    }

    private fun skipSubBlocks(bytes: ByteArray, start: Int): Int {
        var offset = start
        while (offset < bytes.size) {
            val size = bytes[offset].toInt() and 0xff
            offset += 1
            if (size == 0) break
            offset += size
        }
        return offset
    }

    private fun nearestPaletteIndex(bytes: ByteArray, offset: Int, size: Int, rgb: Int): Int {
        if (offset < 0 || size <= 0) return 0
        val targetRed = rgb shr 16 and 0xff
        val targetGreen = rgb shr 8 and 0xff
        val targetBlue = rgb and 0xff
        return (0 until size).minByOrNull { index ->
            val colorOffset = offset + index * 3
            val red = bytes[colorOffset].toInt() and 0xff
            val green = bytes[colorOffset + 1].toInt() and 0xff
            val blue = bytes[colorOffset + 2].toInt() and 0xff
            val dr = red - targetRed
            val dg = green - targetGreen
            val db = blue - targetBlue
            dr * dr + dg * dg + db * db
        } ?: 0
    }

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
        private const val TRANSPARENT_RGB = 0x01fe01
    }
}
