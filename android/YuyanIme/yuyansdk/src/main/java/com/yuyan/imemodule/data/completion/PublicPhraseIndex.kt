package com.yuyan.imemodule.data.completion

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** 公开原词条的精确成员查询，不参与拼音解码或频率排序。 */
internal class PublicPhraseIndex(bytes: ByteBuffer) {
    private val data = bytes.slice().asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)
    private val count: Int
    private val payload: Int

    init {
        require(data.limit() >= 16) { "词条索引头不完整" }
        val magic = "T9WORD1\u0000".toByteArray(Charsets.US_ASCII)
        require(magic.indices.all { data.get(it) == magic[it] }) { "词条索引版本错误" }
        count = data.getInt(8)
        require(count >= 0 && count <= (data.limit() - 16) / 4) { "词条索引数量错误" }
        payload = 16 + count * 4
        require(data.getInt(12) == 0) { "词条索引首偏移错误" }
        var previous = 0
        for (i in 1..count) {
            val offset = data.getInt(12 + i * 4)
            require(offset > previous && offset <= data.limit() - payload) { "词条索引越界" }
            previous = offset
        }
        require(previous == data.limit() - payload) { "词条索引长度不匹配" }
    }

    fun contains(text: String): Boolean {
        if (text.isEmpty()) return false
        val target = text.toByteArray(Charsets.UTF_8)
        var low = 0
        var high = count - 1
        while (low <= high) {
            val middle = (low + high).ushr(1)
            val start = payload + data.getInt(12 + middle * 4)
            val end = payload + data.getInt(16 + middle * 4)
            var comparison = 0
            for (i in 0 until minOf(end - start, target.size)) {
                comparison = (data.get(start + i).toInt() and 255) - (target[i].toInt() and 255)
                if (comparison != 0) break
            }
            if (comparison == 0) comparison = (end - start) - target.size
            when {
                comparison < 0 -> low = middle + 1
                comparison > 0 -> high = middle - 1
                else -> return true
            }
        }
        return false
    }

    companion object {
        private const val ASSET = "completion/public_phrases.t9idx"
        fun load(context: Context): PublicPhraseIndex = context.assets.openFd(ASSET).use { asset ->
            asset.createInputStream().use { input ->
                PublicPhraseIndex(input.channel.map(FileChannel.MapMode.READ_ONLY, asset.startOffset, asset.length))
            }
        }
    }
}
