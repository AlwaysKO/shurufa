package com.yuyan.imemodule.data.completion

import java.io.Reader
import java.util.TreeMap

internal data class OfflineAssociationQuery(
    val partial: List<String>,
    val next: List<String>,
    val trailingPunctuation: Boolean,
) {
    companion object {
        val EMPTY = OfflineAssociationQuery(emptyList(), emptyList(), false)
    }
}

internal class OfflineAssociationIndex private constructor(
    private val keys: Array<String>,
    private val values: Array<OfflineAssociationQuery>,
) {
    fun query(text: String): OfflineAssociationQuery {
        val normalized = text.trimEnd(*TRAILING_PUNCTUATION)
        val hasTrailingPunctuation = normalized.length != text.length
        val tail = normalized.takeLast(MAX_LOOKUP_LENGTH)
        for (length in tail.length downTo MIN_LOOKUP_LENGTH) {
            val index = keys.binarySearch(tail.takeLast(length))
            if (index >= 0) {
                val result = values[index]
                return result.copy(trailingPunctuation = hasTrailingPunctuation)
            }
        }
        // 完整触发词优先；用户已输入部分补全时，仅续完原有 P 表达，不猜 N 句间关系。
        for (length in tail.length downTo MIN_LOOKUP_LENGTH + 1) {
            val suffix = tail.takeLast(length)
            for (prefixLength in length - 1 downTo MIN_LOOKUP_LENGTH) {
                val index = keys.binarySearch(suffix.take(prefixLength))
                if (index < 0) continue
                val entered = suffix.drop(prefixLength)
                val remaining = values[index].partial.filter { it.startsWith(entered) }
                    .map { it.drop(entered.length) }.filter { it.isNotBlank() }.distinct()
                if (remaining.isNotEmpty()) return OfflineAssociationQuery(remaining, emptyList(), hasTrailingPunctuation)
            }
        }
        return OfflineAssociationQuery.EMPTY
    }

    companion object {
        private const val MIN_LOOKUP_LENGTH = 2
        private const val MAX_LOOKUP_LENGTH = 40
        private val TRAILING_PUNCTUATION = charArrayOf('，', '。', '！', '？', '；', '：', ',', '.', '!', '?', ';', ':')

        fun parse(reader: Reader): OfflineAssociationIndex {
            val grouped = TreeMap<String, MutableAssociation>()
            reader.buffered().useLines { lines ->
                lines.forEach { line ->
                    if (line.isBlank() || line.startsWith('#')) return@forEach
                    val fields = line.split('\t', limit = 3)
                    if (fields.size != 3 || fields[0].length < MIN_LOOKUP_LENGTH || fields[2].isBlank()) return@forEach
                    val item = grouped.getOrPut(fields[0]) { MutableAssociation() }
                    val target = if (fields[1] == "N") item.next else item.partial
                    if (fields[2] !in target && target.size < 3) target += fields[2]
                }
            }
            return OfflineAssociationIndex(
                grouped.keys.toTypedArray(),
                grouped.values.map {
                    OfflineAssociationQuery(it.partial.toList(), it.next.toList(), false)
                }.toTypedArray(),
            )
        }
    }

    private class MutableAssociation {
        val partial = mutableListOf<String>()
        val next = mutableListOf<String>()
    }
}
