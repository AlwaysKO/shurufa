package com.yuyan.imemodule.expression.send

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GifDeliveryMetadataTest {
    @get:Rule val temporary = TemporaryFolder()
    private val gif = Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7")
    private val header get() = gif.copyOfRange(0, 19)
    private val frames get() = gif.copyOfRange(19, gif.size)

    private fun extension(id: String = "XMP DataXMP", payload: ByteArray = byteArrayOf(65)): ByteArray =
        ByteArrayOutputStream().apply {
            write(byteArrayOf(0x21, 0xff.toByte(), 11))
            write(id.toByteArray(Charsets.US_ASCII))
            var offset = 0
            while (offset < payload.size) {
                val count = minOf(255, payload.size - offset)
                write(count); write(payload, offset, count); offset += count
            }
            write(0)
        }.toByteArray()

    private fun file(bytes: ByteArray): File = temporary.newFile().apply { writeBytes(bytes) }
    private fun rewritten(file: File): ByteArray = requireNotNull(GifDeliveryMetadata.inspect(file))
        .open(file).use { it.readBytes() }

    @Test fun repeatedXmpIsDeduplicatedWithoutChangingSourceOrFrameBytes() {
        val xmp = extension(payload = ByteArray(1024) { 65 })
        val repeated = ByteArrayOutputStream().apply { repeat(32) { write(xmp) } }.toByteArray()
        val original = header + repeated + frames
        val source = file(original)
        val result = rewritten(source)
        assertArrayEquals(header + xmp + frames, result)
        assertArrayEquals(original, source.readBytes())
        assertNull("已归一化副本幂等，不再改写", GifDeliveryMetadata.inspect(file(result)))
    }

    @Test fun distinctXmpLoopCommentsAndUnknownApplicationBlocksArePreserved() {
        val a = extension(payload = byteArrayOf(65))
        val b = extension(payload = byteArrayOf(66))
        val loop = extension("NETSCAPE2.0", byteArrayOf(1, 0, 0))
        val unknown = extension("UNKNOWN0000", byteArrayOf(42))
        val comment = byteArrayOf(0x21, 0xfe.toByte(), 1, 65, 0)
        val source = file(header + loop + a + comment + b + unknown + a + unknown + frames)
        assertArrayEquals(header + loop + a + comment + b + unknown + unknown + frames, rewritten(source))
    }

    @Test fun ordinaryGifAndASingleXmpDoNotNeedCopies() {
        assertNull(GifDeliveryMetadata.inspect(file(gif)))
        assertNull(GifDeliveryMetadata.inspect(file(header + extension() + frames)))
        assertNull(GifDeliveryMetadata.inspect(file(header + extension("UNKNOWN0000") + extension("UNKNOWN0000") + frames)))
    }

    @Test fun gif87aAndNoGlobalColorTableAreHandledWithoutReencoding() {
        val noPalette = header.copyOfRange(0, 13).apply {
            "GIF87a".toByteArray().copyInto(this)
            this[10] = 0
        }
        val xmp = extension()
        assertArrayEquals(noPalette + xmp + frames, rewritten(file(noPalette + xmp + xmp + frames)))
    }

    @Test fun xmpAfterTheFirstFrameIsNotTouched() {
        val xmp = extension()
        assertNull(GifDeliveryMetadata.inspect(file(gif.dropLast(1).toByteArray() + xmp + xmp + byteArrayOf(0x3b))))
    }

    @Test fun truncationAndUnknownBlockDoNotProduceAPartiallyRewrittenFile() {
        val xmp = extension()
        val broken = listOf(
            byteArrayOf(), header.copyOf(10), header + xmp + xmp,
            header + xmp + xmp + byteArrayOf(0x21, 0xff.toByte(), 11, 1),
            header + xmp + xmp + byteArrayOf(0x2c),
            header + xmp + xmp + byteArrayOf(0x00) + frames,
            "not a gif".toByteArray() + xmp + xmp + frames,
        )
        broken.forEach { assertNull(GifDeliveryMetadata.inspect(file(it))) }
    }

    @Test fun scanningIsBoundedEvenWithLargeMetadataOrManyEmptyBlocks() {
        val xmp = extension()
        assertNull(GifDeliveryMetadata.inspect(file(header + xmp + xmp + extension(payload = ByteArray(1024 * 1024)) + frames)))
        val many = ByteArrayOutputStream().apply { repeat(4096) { write(byteArrayOf(0x21, 0xfe.toByte(), 0)) } }.toByteArray()
        assertNull(GifDeliveryMetadata.inspect(file(header + xmp + xmp + many + frames)))
    }
}
