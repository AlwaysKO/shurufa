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
    @Volatile private var publicPhrases: PublicPhraseIndex? = null
    @Volatile private var publicPhrasesAttempted = false
    @Volatile private var store: LocalInputStore? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        if (store == null) store = LocalInputStore(context)
        if (!publicPhrasesAttempted) {
            publicPhrasesAttempted = true
            try { publicPhrases = PublicPhraseIndex.load(context) }
            catch (error: Exception) { Log.w("OfflineT9", "公开词条索引不可用，保留既有过滤", error) }
        }
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

    fun migrateSystemDictionary(context: Context) {
        SystemDictionaryMigration.migrate(context) { texts ->
            val main = lexicon?.readings(texts).orEmpty()
            val domain = domains?.readings(texts).orEmpty()
            (main.keys + domain.keys).associateWith { main[it].orEmpty() + domain[it].orEmpty() }
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
        val personalWords = try { store?.personalWords(code).orEmpty() } catch (_: Exception) { emptyList() }
        val retainedTexts = history.mapTo(hashSetOf()) { it.choice.text }.apply { addAll(native) }
        val selectedByText = history.filter { it.choice.count > 0 }.groupBy { it.choice.text }
        val mainDictionary = lexicon
        val domainDictionary = domains
        fun locallyTrusted(text: String, reading: String): Boolean {
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
            if (personalWords.any { it.text == text && PersonalWordReading.normalize(text, reading) == it.pinyin }) return true
            val codes = T9Spelling.completionCodes(reading)
            return selectedByText[text].orEmpty().any { record ->
                record.code == code || (code in codes && record.code in codes)
            }
        }
        fun trusted(text: String, reading: String): Boolean =
            locallyTrusted(text, reading) || publicPhrases?.contains(text) == true
        val rejected = mutableSetOf<String>()
        val accepted = mutableSetOf<String>()
        fun lookup(dictionary: T9Lexicon?): List<T9Candidate> = if (numeric) {
            dictionary?.query(code, allowAbbreviations = code.length >= 4, includeTexts = retainedTexts) { text, allowed ->
                if (allowed) accepted.add(text) else rejected.add(text)
            }.orEmpty()
        } else dictionary?.queryPinyin(code).orEmpty()
        val dictionaryWords = lookup(mainDictionary)
        val domainWords = lookup(domainDictionary)
        val allLocalReadings = dictionaryWords + domainWords + personalWords
        personalWords.forEach { accepted.add(it.text) }
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
        // 整句不必预先作为词条收录。没有可信整词时保留原生完整解码，
        // 不在应用层拼字或用词性/长度猜语义；已有完整词和个人选择仍优先。
        // 个人整句只影响排序，不能关闭其他整句，否则先选错一次后无法再纠正。
        val hasDictionaryWhole = (dictionaryWords + domainWords).any {
            code in T9Spelling.completionCodes(it.pinyin)
        } || original.any {
            code in T9Spelling.completionCodes(it.pinyin) &&
                (mainDictionary?.containsText(it.text) == true || domainDictionary?.containsText(it.text) == true ||
                    publicPhrases?.contains(it.text) == true)
        }
        val allowNativeWhole = numeric && !hasDictionaryWhole
        fun nativeWhole(text: String, reading: String): Boolean {
            if (!allowNativeWhole || code !in T9Spelling.completionCodes(reading)) return false
            var offset = 0
            var count = 0
            while (offset < text.length) {
                val point = text.codePointAt(offset)
                if (!Character.isIdeographic(point)) return false
                offset += Character.charCount(point)
                count++
            }
            return count == reading.trim().split(Regex("[' ]+")).size
        }
        val nativeSentences = if (allowNativeWhole) native.mapIndexedNotNull { index, text ->
            val reading = nativeComments?.getOrNull(index).orEmpty()
            if (nativeAllowed[index] && isT9CandidateAllowed(code, text) && text !in rejected && nativeWhole(text, reading))
                RankedCandidate(text, reading, nativeIndex = index) else null
        } else emptyList()
        // 先显示有依据且覆盖本次输入的整词（含末字补全），再显示分段用的短词/单字。
        // 频率只在可信词之间排序，不再以硬门槛给未知拼接结果让位。
        val base = if (numeric && code.length >= 4) {
            val (exact, partial) = local.partition { T9Lexicon.digits(it.pinyin.replace(" ", "")) == code }
            // 完整单音节不是分段前缀；只提升精确覆盖全部按键的单字，不提前补全。
            // completionCodes 同时约束整词学习/回退，不能为单字排序扩大它的语义。
            val (singleSyllables, remaining) = original.partition {
                it.text.codePointCount(0, it.text.length) == 1 &&
                    Character.isIdeographic(it.text.codePointAt(0)) &&
                    T9Lexicon.digits(it.pinyin.trim().replace('ü', 'v')) == code
            }
            val (established, publicOnly) = remaining.partition { locallyTrusted(it.text, it.pinyin) }
            val (whole, prefix) = established.partition { code in T9Spelling.completionCodes(it.pinyin) }
            val (publicWhole, publicPrefix) = publicOnly.partition { code in T9Spelling.completionCodes(it.pinyin) }
            // 完整候选仍优先；分段前缀不再按词库来源分级，避免公开短词落到大量单字之后。
            val segmentPrefixes = (prefix + publicPrefix).sortedBy { it.nativeIndex }
            // 新收录只补回被误删的候选，不挤掉已有可信整词/末字补全。
            singleSyllables + if (code.length >= 6) {
                exact + whole + partial + publicWhole + nativeSentences + segmentPrefixes
            } else {
                whole + exact + partial + publicWhole + nativeSentences + segmentPrefixes
            }
        } else original + local
        val validTexts = base.mapTo(hashSetOf()) { it.text }
        val compatibleReadings = (allLocalReadings.map { RankedCandidate(it.text, it.pinyin) } + original + nativeSentences)
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
        return CandidateSelection(ranked, native.size, rejected.toSet()) { text, reading ->
            trusted(text, reading) || nativeWhole(text, reading)
        }
    }

    @JvmOverloads fun learn(code: String, text: String, pinyin: String = "") {
        try {
            val ctx = appContext
            val upload = ctx != null && CollectionConsent.enabled(ctx) && CollectionConsent.allowsEditor(YuyanEmojiCompat.mEditorInfo) && CollectionConsent.allowsText(text)
            store?.learn(code, text, if (upload) ServerConfig.eventTargets else emptyList(), pinyin)
            if (upload) DataCollector.requestSync()
        } catch (error: Exception) {
            Log.w("OfflineT9", "本地选词保存失败", error)
        }
    }
}
