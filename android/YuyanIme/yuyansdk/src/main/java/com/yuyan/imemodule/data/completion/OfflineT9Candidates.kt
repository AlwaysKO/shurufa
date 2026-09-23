package com.yuyan.imemodule.data.completion

import com.yuyan.inputmethod.util.T9Spelling
import android.content.Context
import android.util.Log
import android.os.Handler
import android.os.Looper
import com.yuyan.imemodule.data.collect.PendingChoice
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
    @Volatile private var inputCompletions: InputCompletionIndex? = null
    @Volatile private var inputCompletionsAttempted = false
    @Volatile private var store: LocalInputStore? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        if (store == null) store = LocalInputStore(context)
        // 上次进程结束时未结算的奖励仍在SQLite中；旧定时器丢失不等于丢学习。
        store?.settleLearning()
        scheduleLearningSettlement()
        if (!publicPhrasesAttempted) {
            publicPhrasesAttempted = true
            try { publicPhrases = PublicPhraseIndex.load(context) }
            catch (error: Exception) { Log.w("OfflineT9", "公开词条索引不可用，保留既有过滤", error) }
        }
        if (!inputCompletionsAttempted) {
            inputCompletionsAttempted = true
            try { inputCompletions = InputCompletionIndex.load(context) }
            catch (error: Exception) { Log.w("OfflineT9", "简拼补全索引不可用，保留既有输入", error) }
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
        if (code.length in 1..2 && code.all { it in '2'..'9' }) {
            val history = try { store?.learned(code).orEmpty().associateBy { it.text } }
                catch (_: Exception) { emptyMap() }
            val now = System.currentTimeMillis()
            // 不调用会去重/注入历史项的通用rank，逐项保留原生索引和同字异读。
            val ranked = native.mapIndexed { index, text -> RankedCandidate(text, nativeComments?.getOrNull(index).orEmpty(), index) }
                .sortedWith(compareByDescending<RankedCandidate> {
                    PersonalCandidateRanker.recentSelection(history[it.text]?.lastUsed, now)
                }.thenByDescending {
                    val index = requireNotNull(it.nativeIndex)
                    val choice = history[it.text]
                    (if (index == 0) 2.0 else 1.0 / (index + 1)) +
                        (choice?.let { h -> PersonalCandidateRanker.decay(h.weight, h.lastUsed, now) } ?: 0.0)
                })
            return CandidateSelection(ranked, native.size)
        }
        val numeric = code.length in 3..30 && code.all { it in '2'..'9' }
        val letters = code.length in 2..30 && code.all { it in 'a'..'z' }
        if (!numeric && !letters) return CandidateSelection(
            native.mapIndexed { index, text -> RankedCandidate(text, nativeIndex = index) }, native.size,
        )
        val history = try { store?.relatedLearned(code).orEmpty() } catch (_: Exception) { emptyList() }
        val personalWords = try { store?.personalWords(code).orEmpty() } catch (_: Exception) { emptyList() }
        val completions = inputCompletions?.query(code).orEmpty()
        val extraReadings = (completions + personalWords).mapNotNull { word ->
            InputSpellingMatch.match(code, word.pinyin)?.let { (word.text to word.pinyin) to it }
        }.toMap()
        fun extraMatch(text: String, reading: String): InputSpellingMatch? =
            extraReadings[text to PersonalWordReading.normalize(text, reading)]
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
            val codes = T9Spelling.completionCodes(reading, minLength = 3)
            return selectedByText[text].orEmpty().any { record ->
                record.code == code || (code in codes && record.code in codes)
            }
        }
        fun trusted(text: String, reading: String): Boolean =
            locallyTrusted(text, reading) || publicPhrases?.contains(text) == true || extraMatch(text, reading) != null
        val rejected = mutableSetOf<String>()
        val accepted = mutableSetOf<String>()
        fun lookup(dictionary: T9Lexicon?): List<T9Candidate> = if (numeric) {
            // 多个高频同码词不能把完整日常词裁掉；仍是固定上限，学习词可越过上限召回。
            dictionary?.query(code, limit = 32, includeTexts = retainedTexts) { text, allowed ->
                if (allowed) accepted.add(text) else rejected.add(text)
            }.orEmpty()
        } else dictionary?.queryPinyin(code).orEmpty()
        val dictionaryWords = lookup(mainDictionary)
        val domainWords = lookup(domainDictionary)
        val allLocalReadings = dictionaryWords + domainWords + personalWords
        (personalWords + completions).forEach { accepted.add(it.text) }
        val common = if (numeric) allLocalReadings.groupBy { it.text }.values
            .map { readings -> readings.maxBy { it.frequency } }.sortedByDescending { it.frequency }
        else allLocalReadings.distinctBy { it.text }
        // 原生读音直接校验，不能依赖本地词库是否收录该词。
        val nativeAllowed = native.indices.map { index ->
            nativeComments == null || T9Spelling.allows(code, native[index], nativeComments.getOrNull(index).orEmpty()) ||
                extraMatch(native[index], nativeComments.getOrNull(index).orEmpty()) != null
        }
        if (numeric && nativeComments != null) {
            native.indices.filter { nativeAllowed[it] }.forEach { accepted.add(native[it]) }
        }
        // 多音字或两个词库中的不同读音，只要存在合法拼写就不误删。
        rejected.removeAll(accepted)
        // 新匹配独立混排，不进入旧整词分组或关闭原生整句回退。
        val local = common.filter { extraMatch(it.text, it.pinyin) == null }.map { RankedCandidate(it.text, it.pinyin) }
        val localReadings = local.associate { it.text to it.pinyin }
        val original = native.mapIndexedNotNull { index, text ->
            val nativeReading = nativeComments?.getOrNull(index) ?: localReadings[text].orEmpty()
            val match = extraMatch(text, nativeReading)
            val reading = if (numeric || match != null) nativeReading else localReadings[text].orEmpty()
            if (!nativeAllowed[index] || (match == null && !isT9CandidateAllowed(code, text)) || text in rejected || !trusted(text, reading)) null
            else RankedCandidate(text, reading, nativeIndex = index, inputMatch = match)
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
        } else if (numeric) {
            // 精确单音节保留原生字序，其余优先采用词典词频，而非无条件保留原生首项。
            val singleSyllables = original.filter {
                it.text.codePointCount(0, it.text.length) == 1 &&
                    T9Lexicon.digits(it.pinyin.trim().replace('ü', 'v')) == code
            }
            singleSyllables + local + original
        } else original + local
        val extras = (completions + personalWords).distinctBy { it.text to it.pinyin }.mapNotNull { word ->
            extraMatch(word.text, word.pinyin)?.let { RankedCandidate(word.text, word.pinyin, inputMatch = it) }
        }
        val initials = extras.filter { it.inputMatch?.kind == InputMatchKind.THREE_INITIALS }
        val phrases = extras.filter { it.inputMatch?.kind == InputMatchKind.PHRASE_PREFIX }
            .distinctBy { it.text }
        val wholePhrases = extras.filter { it.inputMatch?.kind == InputMatchKind.WHOLE_PHRASE }
        val withWhole = wholePhrases + base
        val withInitials = if (initials.isEmpty()) withWhole else {
            // 已有完整单音节/整词继续优先；三字简拼排在只覆盖部分按键的前缀之前。
            // 无注释的兼容调用无法核对单字读音，继续保留其原生优先级。
            val (whole, prefix) = withWhole.partition {
                (nativeComments == null && it.nativeIndex != null && it.text.codePointCount(0, it.text.length) == 1) ||
                T9Lexicon.digits(it.pinyin.replace(" ", "").replace("'", "")) == code ||
                    code in T9Spelling.completionCodes(it.pinyin, minLength = 3)
            }
            whole + initials + prefix
        }
        val combined = if (phrases.isEmpty()) withInitials else withInitials.take(1) + phrases + withInitials.drop(1)
        val validTexts = combined.mapTo(hashSetOf()) { it.text }
        val compatibleReadings = (allLocalReadings.map { RankedCandidate(it.text, it.pinyin) } + original + nativeSentences)
            .groupBy { it.text }.mapValues { (_, readings) ->
                readings.map { T9Spelling.completionCodes(it.pinyin, minLength = 3) }.distinct()
            }
        val now = System.currentTimeMillis()
        val learned = history.filter { record ->
            val text = record.choice.text
            record.choice.count > 0 && (text in validTexts || isT9CandidateAllowed(code, text)) && text !in rejected &&
                (!numeric || nativeComments == null || text in validTexts) &&
                (record.code == code || compatibleReadings[text].orEmpty().any { codes ->
                    code in codes && record.code in codes
                })
        }.groupBy { it.choice.text }.map { (text, records) ->
            ChoiceEvidence(text, records.sumOf {
                PersonalCandidateRanker.decay(it.choice.weight, it.choice.lastUsed, now)
            }, now, lastSelectedAt = if (numeric)
                records.filter { it.code == code }.maxOfOrNull { it.choice.lastUsed } else null)
        }
        // 明确手工词只在原拼写边界内获得基础先验，不写假点击，不改变锁音原生链。
        val preferred = try { store?.personalWords(code, preferredOnly = true).orEmpty() } catch (_: Exception) { emptyList() }
        var phraseCount = 0
        val ranked = PersonalCandidateRanker.rank(preferred.map {
            RankedCandidate(it.text, it.pinyin, inputMatch = extraMatch(it.text, it.pinyin))
        } + combined, learned, now).filter {
            it.inputMatch?.kind != InputMatchKind.PHRASE_PREFIX || ++phraseCount <= 2
        }
        return CandidateSelection(ranked, native.size, rejected.toSet(), ::extraMatch) { text, reading ->
            trusted(text, reading) || nativeWhole(text, reading)
        }
    }

    /** 锁音/分段只重排原生现有项：不注入、过滤或按文字合并不同读音的原生索引。 */
    fun rankNative(native: List<RankedCandidate>, nativeCount: Int): CandidateSelection {
        val readings = native.map { PersonalWordReading.normalize(it.text, it.pinyin) }
        val codes = readings.map { it?.let { reading -> T9Lexicon.digits(reading.replace(" ", "")) } }
        val now = System.currentTimeMillis()
        val history = codes.filterNotNull().distinct().associateWith { code ->
            try { store?.learned(code).orEmpty().associateBy { it.text } }
            catch (_: Exception) { emptyMap() }
        }
        val ranked = native.withIndex().sortedWith(compareByDescending<IndexedValue<RankedCandidate>> { (index, candidate) ->
            val choice = history[codes[index]]?.get(candidate.text)?.takeIf { it.count > 0 }
            PersonalCandidateRanker.recentSelection(choice?.lastUsed, now)
        }.thenByDescending { (index, candidate) ->
            val prior = if (index == 0) 2.0 else 1.0 / (index + 1)
            val choice = history[codes[index]]?.get(candidate.text)
            prior + if (choice != null && choice.count > 0)
                PersonalCandidateRanker.decay(choice.weight, choice.lastUsed, now) else 0.0
        }).map { it.value }
        return CandidateSelection(ranked, nativeCount)
    }

    private fun learningChoices(selection: T9CommitSelection): List<PendingChoice> = buildList {
        add(PendingChoice(selection.code, selection.text, selection.pinyin))
        if (selection.parts.size > 1 &&
            selection.parts.joinToString("") { it.text } == selection.text &&
            selection.parts.joinToString(" ") { it.pinyin } == selection.pinyin &&
            PersonalWordReading.matches(selection.code, selection.pinyin)) {
            selection.parts.forEach { part ->
                val reading = PersonalWordReading.normalize(part.text, part.pinyin) ?: return@forEach
                add(PendingChoice(T9Lexicon.digits(reading.replace(" ", "")), part.text, reading))
            }
        }
    }

    /** 调用方已确认宿主成功及隐私资格；整词和实际选中的段只交接一次。 */
    fun learn(selection: T9CommitSelection) {
        learningChoices(selection).forEach { learn(it.code, it.text, it.pinyin) }
    }

    /** 本机即时排序可见，17秒严格观察窗内不出现在任何上传/备份里。 */
    fun learnTemporarily(selection: T9CommitSelection, id: String): String? {
        val current = store ?: return null
        return try {
            val upload = appContext?.let { CollectionConsent.enabled(it) } == true &&
                CollectionConsent.allowsEditor(YuyanEmojiCompat.mEditorInfo) && CollectionConsent.allowsText(selection.text)
            current.stageLearning(id, learningChoices(selection), if (upload) ServerConfig.eventTargets else emptyList())
            scheduleLearningSettlement()
            id
        } catch (error: Exception) {
            Log.w("OfflineT9", "临时学习保存失败", error)
            null
        }
    }

    fun cancelLearning(id: String) {
        try { store?.cancelLearning(id) }
        catch (error: Exception) { Log.w("OfflineT9", "临时学习撤销失败", error) }
    }

    private fun scheduleLearningSettlement() {
        val current = store ?: return
        // 捕获实例避免旧会话定时器触碰重建后的数据库；事务负责重复调用幂等。
        Handler(Looper.getMainLooper()).postDelayed({
            if (store === current) {
                try {
                    current.settleLearning()
                    if (appContext?.let { CollectionConsent.enabled(it) } == true) DataCollector.requestSync()
                } catch (error: Exception) { Log.w("OfflineT9", "临时学习结算失败，下次重试", error) }
            }
        }, CorrectionLearningTracker.REWARD_WINDOW_MS + 1)
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
