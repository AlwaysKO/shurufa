package com.yuyan.imemodule.data.completion

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** 只映射索引；按键查询最多解码一个三键桶或一个固定表达前缀，不扫描全库。 */
internal class InputCompletionIndex(bytes: ByteBuffer) {
    private val data = bytes.slice().asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)
    private val count: Int
    private val payload: Int

    init {
        require(data.limit() >= 16)
        val magic = "T9COMP1\u0000".toByteArray(Charsets.US_ASCII)
        require(magic.indices.all { data.get(it) == magic[it] })
        count = data.getInt(8)
        require(count >= 0 && count <= (data.limit() - 16) / 4)
        payload = 16 + count * 4
        require(data.getInt(12) == 0)
        var previous = 0
        for (i in 1..count) {
            val offset = data.getInt(12 + i * 4)
            require(offset > previous && offset <= data.limit() - payload)
            previous = offset
        }
        require(previous == data.limit() - payload)
    }

    private fun key(index: Int): String {
        val start = payload + data.getInt(12 + index * 4)
        val end = payload + data.getInt(16 + index * 4)
        val result = StringBuilder()
        for (i in start until end) {
            val value = data.get(i).toInt()
            if (value == 9) return result.toString()
            result.append(value.toChar())
        }
        return ""
    }

    fun query(code: String): List<T9Candidate> {
        val numeric = code.isNotEmpty() && code.all { it in '2'..'9' }
        if (!numeric && (code.isEmpty() || code.any { it !in 'a'..'z' })) return emptyList()
        val short = numeric && code.length == 3
        if (!short && code.length !in 4..30) return emptyList()
        val prefix = (if (short) "a" else if (numeric) "n" else "p") + code
        var low = 0
        var high = count
        while (low < high) {
            val middle = (low + high).ushr(1)
            if (key(middle) < prefix) low = middle + 1 else high = middle
        }
        val candidates = ArrayList<T9Candidate>()
        for (index in low until minOf(count, low + MAX_BUCKET)) {
            val found = key(index)
            if (if (short) found != prefix else !found.startsWith(prefix)) break
            val start = payload + data.getInt(12 + index * 4)
            val end = payload + data.getInt(16 + index * 4)
            val row = ByteArray(end - start)
            data.duplicate().apply { position(start); get(row) }
            val fields = row.toString(Charsets.UTF_8).split('\t')
            if (fields.size != 4) continue
            val reading = PersonalWordReading.normalize(fields[1], fields[2]) ?: continue
            if (InputSpellingMatch.match(code, reading) == null) continue
            candidates.add(T9Candidate(fields[1], reading, fields[3].toLongOrNull() ?: 0))
        }
        return if (short) candidates else candidates.sortedWith(
            compareByDescending<T9Candidate> { it.frequency }.thenBy { it.text.length }.thenBy { it.text },
        ).distinctBy { it.text }.take(2)
    }

    companion object {
        private const val MAX_BUCKET = 4096
        fun load(context: Context): InputCompletionIndex = context.assets.openFd("completion/input_completion.t9idx").use { asset ->
            asset.createInputStream().use { input ->
                InputCompletionIndex(input.channel.map(FileChannel.MapMode.READ_ONLY, asset.startOffset, asset.length))
            }
        }
    }
}
