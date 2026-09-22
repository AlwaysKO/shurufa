package com.yuyan.imemodule.expression

/** Complete-phrase rules, kept aligned with server queryMatching.ts by a shared fixture. */
internal object ExpressionQueryMatching {
    // ECMAScript whitespace: Unicode separators, ASCII whitespace and BOM (not Java default \s).
    fun normalize(value: String): String = value.lowercase()
        .replace(Regex("[\\x09-\\x0D\\p{Z}\\uFEFF\\p{P}\\p{S}]+"), "")

    /** 自动推荐只去首尾空白和句末标点，不折叠大小写、内部文字或符号。 */
    fun normalizeAutomatic(value: String): String = value
        .replace(Regex("^[\\x09-\\x0D\\p{Z}\\uFEFF]+|[\\x09-\\x0D\\p{Z}\\uFEFF]+$"), "")
        .replace(Regex("[\\x09-\\x0D\\p{Z}\\uFEFF\\p{P}]+$"), "")

    val groups = listOf(
        listOf("赞", "点赞", "给你点赞", "太棒了"),
        listOf("谢谢", "感谢", "多谢", "感激"),
        listOf("打闹", "玩闹", "打你", "打我", "揍你", "揍我", "捶你", "捶我"),
        listOf("追赶", "抓你", "抓我", "追你", "追我", "捉你", "捉我"),
        listOf("难过", "伤心", "不开心", "不高兴", "悲伤"),
        listOf("开心", "高兴", "快乐"),
        listOf("不要", "不可以", "不行", "拒绝"),
        listOf("可以", "好的", "好呀", "同意", "没问题"),
        listOf("喜欢", "爱你", "心动"),
        listOf("对不起", "抱歉", "不好意思"),
        listOf("哈哈", "笑死", "好笑"),
        listOf("震惊", "惊讶", "惊呆", "吓一跳"),
        listOf("生气", "愤怒", "气死"),
        listOf("加油", "努力", "坚持"),
        listOf("收到", "明白", "知道了"),
        listOf("再见", "拜拜", "回见"),
        listOf("抱抱", "拥抱"),
    )
    private val negativePrefix = Regex("(?:不|没|没有|别|不要|不会|不能|不想|不愿|不许|禁止)(?:再|去|来|要|会|想|能|愿意|真的|太|很|要来|打算|准备|计划|过来|过去){0,3}$")

    private fun containsPositive(query: String, phrase: String): Boolean {
        var start = query.indexOf(phrase)
        while (start >= 0) {
            val phoneContext = (phrase == "打你" || phrase == "打我") &&
                Regex("^的?(?:电话|手机|号码)").containsMatchIn(query.substring(start + phrase.length))
            if (!phoneContext && !negativePrefix.containsMatchIn(query.substring(0, start))) return true
            start = query.indexOf(phrase, start + 1)
        }
        return false
    }

    // 完整原句 > 句内核心短语 > 近义词；热度不应覆盖语义相关性。
    fun score(query: String, values: List<String>): Double {
        var score = 0.0
        var blocked = false
        for (value in values) {
            val phrase = normalize(value)
            if (phrase.isEmpty()) continue
            if (phrase == query) score = maxOf(score, 1000.0)
            if (phrase.length < 2) continue
            if (containsPositive(query, phrase)) score = maxOf(score, 900.0 + phrase.length.coerceAtMost(99))
            else if (query.contains(phrase)) blocked = true
            for (group in groups) {
                if (group.none { containsPositive(phrase, it) }) continue
                if (group.any { containsPositive(query, it) }) score = maxOf(score, 800.0)
                else if (group.any { query.contains(it) }) blocked = true
            }
        }
        return if (score > 0) score else if (blocked) -1.0 else 0.0
    }
}
