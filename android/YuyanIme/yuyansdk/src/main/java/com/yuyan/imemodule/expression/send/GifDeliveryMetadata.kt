package com.yuyan.imemodule.expression.send

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.io.SequenceInputStream

/**
 * 某些系统的 GIF 流式解码无法处理首帧前堆积的重复 XMP，尽管数组解码/预览正常。
 * 只去重完全相同的 XMP 块；保留第一份、其他元数据及全部动画字节，不重编码或改原图。
 */
internal object GifDeliveryMetadata {
    private const val MAX_PREFIX_BYTES = 1024L * 1024
    private const val MAX_EXTENSIONS = 4096
    private val XMP_ID = "XMP DataXMP".toByteArray(Charsets.US_ASCII)

    class Rewrite(private val prefix: ByteArray, private val sourceOffset: Long) {
        fun open(file: File): InputStream {
            val remainder = file.inputStream()
            try {
                remainder.channel.position(sourceOffset)
                return SequenceInputStream(ByteArrayInputStream(prefix), remainder)
            } catch (error: Exception) {
                remainder.close()
                throw error
            }
        }
    }

    /** 无重复、截断、非 GIF 或超扫描预算时不猜测改写；没有整图读取或帧解码。 */
    fun inspect(file: File): Rewrite? = RandomAccessFile(file, "r").use { input ->
        val limit = minOf(input.length(), MAX_PREFIX_BYTES)
        fun read(count: Int): ByteArray {
            if (input.filePointer + count > limit) throw EOFException()
            return ByteArray(count).also(input::readFully)
        }
        fun byte(): Int {
            if (input.filePointer >= limit) throw EOFException()
            return input.readUnsignedByte()
        }
        try {
            val header = read(13)
            if (header.copyOfRange(0, 6).toString(Charsets.US_ASCII) !in listOf("GIF87a", "GIF89a")) return null
            val prefix = ByteArrayOutputStream().apply { write(header) }
            val packed = header[10].toInt() and 255
            if (packed and 128 != 0) prefix.write(read(3 * (1 shl ((packed and 7) + 1))))
            // ISO-8859-1 是逐字节一一映射；按完整块比较，不凭长度或哈希相同删不同元数据。
            val seenXmp = HashSet<String>()
            var changed = false
            repeat(MAX_EXTENSIONS) {
                val start = input.filePointer
                when (byte()) {
                    0x2c -> return if (changed && input.length() - start >= 11)
                        Rewrite(prefix.toByteArray(), start) else null
                    0x21 -> {
                        val label = byte()
                        val block = ByteArrayOutputStream().apply { write(0x21); write(label) }
                        val firstSize = byte()
                        val first = read(firstSize)
                        block.write(firstSize)
                        block.write(first)
                        if (firstSize != 0) {
                            while (true) {
                                val size = byte()
                                block.write(size)
                                if (size == 0) break
                                block.write(read(size))
                            }
                        }
                        val bytes = block.toByteArray()
                        val xmp = label == 0xff && first.contentEquals(XMP_ID)
                        if (xmp && !seenXmp.add(bytes.toString(Charsets.ISO_8859_1))) changed = true
                        else prefix.write(bytes)
                    }
                    else -> return null
                }
            }
            null
        } catch (_: EOFException) {
            null
        }
    }
}
