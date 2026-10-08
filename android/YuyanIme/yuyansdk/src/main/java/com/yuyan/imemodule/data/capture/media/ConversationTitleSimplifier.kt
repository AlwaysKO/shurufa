package com.yuyan.imemodule.data.capture.media

/** 固定OpenCC字词表的标题繁转简；不处理正文，不做近似姓名匹配。采集后台线程首次加载。 */
internal object ConversationTitleSimplifier {
    private data class Dictionary(val entries: Map<String, String>, val phraseWidths: Map<Int, Int>)
    private val dictionary by lazy {
        val entries = linkedMapOf<String, String>()
        val widths = mutableMapOf<Int, Int>()
        requireNotNull(javaClass.getResourceAsStream("/chat-title-t2s.tsv")) {
            "Missing bundled conversation title dictionary"
        }.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.filter { it.isNotEmpty() && !it.startsWith('#') }.forEach { line ->
                val pair = line.split('\t', limit = 2)
                require(pair.size == 2)
                entries[pair[0]] = pair[1]
                val first = pair[0].codePointAt(0)
                widths[first] = maxOf(widths[first] ?: 0, pair[0].length)
            }
        }
        Dictionary(entries, widths)
    }

    fun simplify(raw: String): String {
        if (raw.isEmpty() || raw.all { it.code < 128 }) return raw
        val data = dictionary
        return buildString(raw.length) {
            var offset = 0
            while (offset < raw.length) {
                val cp = raw.codePointAt(offset)
                var consumed = Character.charCount(cp)
                var replacement: String? = null
                val maxWidth = minOf(data.phraseWidths[cp] ?: consumed, raw.length - offset)
                for (width in maxWidth downTo consumed) {
                    val value = data.entries[raw.substring(offset, offset + width)]
                    if (value != null) { replacement = value; consumed = width; break }
                }
                if (replacement != null) append(replacement) else appendCodePoint(cp)
                offset += consumed
            }
        }
    }
}
