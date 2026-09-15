package com.yuyan.imemodule.data.completion

import com.yuyan.inputmethod.util.T9Spelling
import android.content.Context
import android.util.Log
import com.yuyan.imemodule.data.collect.LocalInputStore
import com.yuyan.imemodule.data.collect.CollectionConsent
import com.yuyan.imemodule.data.collect.DataCollector
import com.yuyan.imemodule.data.collect.ServerConfig
import com.yuyan.imemodule.data.emojicon.YuyanEmojiCompat
import java.util.zip.GZIPInputStream

internal object OfflineT9Candidates {
    private var appContext: Context? = null
    @Volatile private var lexicon: T9Lexicon? = null
    @Volatile private var domains: T9Lexicon? = null
    @Volatile private var store: LocalInputStore? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        if (store == null) store = LocalInputStore(context)
        if (lexicon != null) return
        try {
            lexicon = context.assets.open("completion/t9_lexicon.tsv.gzip").use { stream ->
                GZIPInputStream(stream).reader().use(T9Lexicon::parse)
            }
            domains = context.assets.open("completion/chinese_domains.tsv").reader().use(T9Lexicon::parse)
        } catch (error: Exception) {
            Log.w("OfflineT9", "离线词典加载失败，回退 Rime", error)
        }
    }

    fun query(code: String, native: List<String> = emptyList()): List<RankedCandidate> =
        select(code, native).firstPage

    fun select(code: String, native: List<String> = emptyList(), nativeComments: List<String>? = null): CandidateSelection {
        val numeric = code.length in 3..30 && code.all { it in '2'..'9' }
        val letters = code.length in 2..30 && code.all { it in 'a'..'z' }
        if (!numeric && !letters) return CandidateSelection(
            native.mapIndexed { index, text -> RankedCandidate(text, nativeIndex = index) }, native.size,
        )
        val history = try { store?.relatedLearned(code).orEmpty() } catch (_: Exception) { emptyList() }
        val retainedTexts = history.mapTo(hashSetOf()) { it.choice.text }.apply { addAll(native) }
        val selectedByText = history.filter { it.choice.count > 0 }.groupBy { it.choice.text }
        val mainDictionary = lexicon
        val domainDictionary = domains
        fun trusted(text: String, reading: String): Boolean {
            if (!numeric) return true
            var offset = 0
            var hanCount = 0
            while (offset < text.length) {
                val point = text.codePointAt(offset)
                if (Character.isIdeographic(point)) hanCount++
                offset += Character.charCount(point)
            }
            // 保留单字、纯表情/符号；多个汉字不能通过夹杂符号绕过整词依据。
            if (hanCount <= 1) return true
            if (mainDictionary?.containsText(text) == true || domainDictionary?.containsText(text) == true) return true
            val codes = T9Spelling.completionCodes(reading)
            return selectedByText[text].orEmpty().any { record ->
                record.code == code || (code in codes && record.code in codes)
            }
        }
        val rejected = mutableSetOf<String>()
        val accepted = mutableSetOf<String>()
        fun lookup(dictionary: T9Lexicon?): List<T9Candidate> = if (numeric) {
            dictionary?.query(code, allowAbbreviations = code.length >= 4, includeTexts = retainedTexts) { text, allowed ->
                if (allowed) accepted.add(text) else rejected.add(text)
            }.orEmpty()
        } else dictionary?.queryPinyin(code).orEmpty()
        val dictionaryWords = lookup(mainDictionary)
        val domainWords = lookup(domainDictionary)
        val allLocalReadings = dictionaryWords + domainWords
        val common = if (numeric) allLocalReadings.groupBy { it.text }.values
            .map { readings -> readings.maxBy { it.frequency } }.sortedByDescending { it.frequency }
        else allLocalReadings.distinctBy { it.text }
        // 原生读音直接校验，不能依赖本地词库是否收录该词。
        val nativeAllowed = native.indices.map { index ->
            nativeComments == null || T9Spelling.allows(code, native[index], nativeComments.getOrNull(index).orEmpty())
        }
        if (numeric && nativeComments != null) {
            native.indices.filter { nativeAllowed[it] }.forEach { accepted.add(native[it]) }
        }
        // 多音字或两个词库中的不同读音，只要存在合法拼写就不误删。
        rejected.removeAll(accepted)
        val local = common.map { RankedCandidate(it.text, it.pinyin) }
        val localReadings = local.associate { it.text to it.pinyin }
        val original = native.mapIndexedNotNull { index, text ->
            val reading = if (numeric) nativeComments?.getOrNull(index) ?: localReadings[text].orEmpty() else ""
            if (!nativeAllowed[index] || !isT9CandidateAllowed(code, text) || text in rejected || !trusted(text, reading)) null
            else RankedCandidate(text, reading, nativeIndex = index)
        }
        // 先显示有依据且覆盖本次输入的整词（含末字补全），再显示分段用的短词/单字。
        // 频率只在可信词之间排序，不再以硬门槛给未知拼接结果让位。
        val base = if (numeric && code.length >= 4) {
            val (exact, partial) = local.partition { T9Lexicon.digits(it.pinyin.replace(" ", "")) == code }
            val (whole, prefix) = original.partition { code in T9Spelling.completionCodes(it.pinyin) }
            if (code.length >= 6) exact + whole + partial + prefix else whole + exact + partial + prefix
        } else original + local
        val validTexts = base.mapTo(hashSetOf()) { it.text }
        val compatibleReadings = (allLocalReadings.map { RankedCandidate(it.text, it.pinyin) } + original)
            .groupBy { it.text }.mapValues { (_, readings) ->
                readings.map { T9Spelling.completionCodes(it.pinyin) }.distinct()
            }
        val now = System.currentTimeMillis()
        val learned = history.filter { record ->
            val text = record.choice.text
            record.choice.count > 0 && isT9CandidateAllowed(code, text) && text !in rejected &&
                (!numeric || nativeComments == null || text in validTexts) &&
                (record.code == code || compatibleReadings[text].orEmpty().any { codes ->
                    code in codes && record.code in codes
                })
        }.groupBy { it.choice.text }.map { (text, records) ->
            ChoiceEvidence(text, records.sumOf {
                PersonalCandidateRanker.decay(it.choice.weight, it.choice.lastUsed, now)
            }, now)
        }
        val ranked = PersonalCandidateRanker.rank(base, learned, now)
        return CandidateSelection(ranked, native.size, rejected.toSet(), ::trusted)
    }

    fun learn(code: String, text: String) {
        try {
            val ctx = appContext
            val upload = ctx != null && CollectionConsent.enabled(ctx) && CollectionConsent.allowsEditor(YuyanEmojiCompat.mEditorInfo) && CollectionConsent.allowsText(text)
            store?.learn(code, text, if (upload) ServerConfig.eventTargets else emptyList())
            if (upload) DataCollector.requestSync()
        } catch (error: Exception) {
            Log.w("OfflineT9", "本地选词保存失败", error)
        }
    }
}
