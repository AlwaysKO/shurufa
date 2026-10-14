package com.yuyan.imemodule.data.completion

import com.yuyan.inputmethod.util.T9Spelling
import android.content.Context
import android.util.Log
import android.os.SystemClock
import com.yuyan.imemodule.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.yuyan.imemodule.data.collect.PendingChoice
import com.yuyan.imemodule.data.collect.LocalInputStore
import com.yuyan.imemodule.data.collect.CollectionConsent
import com.yuyan.imemodule.data.collect.DataCollector
import com.yuyan.imemodule.data.collect.ServerConfig
import com.yuyan.imemodule.data.emojicon.YuyanEmojiCompat
import java.util.zip.GZIPInputStream

internal object OfflineT9Candidates {
    private val correctionHints = ConfirmedCorrectionHints()
    private val readingSeparators = Regex("[' ]+")
    private val learningScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
        if (store == null) {
            correctionHints.clear()
            store = LocalInputStore(context)
        }
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

    fun blockedCandidateTexts(): Set<String> = store?.blockedCandidateTexts().orEmpty()

    fun select(code: String, native: List<String> = emptyList(), nativeComments: List<String>? = null): CandidateSelection =
        selectInput(code, native, nativeComments, null)

    internal fun selectLocked(input: String, native: List<String>, nativeComments: List<String>): CandidateSelection {
        val constraint = T9InputConstraint.parse(input) ?: return rankNative(
            native.mapIndexed { index, text -> RankedCandidate(text, nativeComments.getOrElse(index) { "" }, index) }, native.size)
        return selectInput(constraint.digits, native, nativeComments, constraint)
    }

    private fun selectInput(code: String, native: List<String>, nativeComments: List<String>?, constraint: T9InputConstraint?): CandidateSelection {
        val timing = if (BuildConfig.DEBUG && appContext != null) LongArray(7).also { it[0] = SystemClock.uptimeMillis() } else null
        // 带音节的明确约束仅用于锁音入口；普通字母全拼维持原来的处理。
        if (constraint == null && '\'' in code && code.any { it in 'a'..'z' }) {
            val parsed = T9InputConstraint.parse(code)
            if (parsed != null) return selectInput(parsed.digits, native, nativeComments, parsed)
        }
        fun matchesConstraint(text: String, reading: String): Boolean = constraint == null ||
            text.none { Character.isIdeographic(it.code) } || constraint.matches(reading, allowPartial = true)
        val blocked = blockedCandidateTexts()
        if (code.length in 1..2 && code.all { it in '2'..'9' }) {
            val history = try { store?.learned(code).orEmpty().associateBy { it.text } }
                catch (_: Exception) { emptyMap() }
            val now = System.currentTimeMillis()
            val original = native.mapIndexed { index, text -> RankedCandidate(text, nativeComments?.getOrNull(index).orEmpty(), index) }
                .filter { it.text !in blocked && matchesConstraint(it.text, it.pinyin) }
            val shortWords = if (code.length == 2 && constraint == null) {
                // 先用同源公共索引排序，再补现有词典；不直接比较不同来源的原始词频。
                val indexed = inputCompletions?.query(code).orEmpty()
                (indexed + (if (indexed.isEmpty())
                    lexicon?.queryShortWords(code, includeTexts = history.keys).orEmpty() +
                        domains?.queryShortWords(code, includeTexts = history.keys).orEmpty() else emptyList()) +
                    (try { store?.personalWords(code).orEmpty() } catch (_: Exception) { emptyList() }))
                    .distinctBy { it.text }.filter { it.text !in native }
                    .mapNotNull { word -> InputSpellingMatch.shortWord(code, word.pinyin)?.let {
                        RankedCandidate(word.text, word.pinyin, inputMatch = it, lexicalEvidence = "dictionary")
                    } }
            } else emptyList()
            // 保留前两个原生项及所有同字异读的相对顺序，短词也能在首屏出现。
            val base = original.take(2) + shortWords + original.drop(2)
            val ranked = base.mapIndexed { index, candidate ->
                    val choice = history[candidate.text]
                    PersonalCandidateRanker.score(index, choice?.weight ?: 0.0,
                        choice?.lastUsed ?: now, choice?.lastUsed, now, correctionHints.at(code, candidate.text, now)) to candidate
                }.sortedByDescending { it.first }.map { it.second }
            return CandidateSelection(ranked, native.size, blockedTexts = blocked, acceptNative = ::matchesConstraint)
        }
        val numeric = code.length in 3..30 && code.all { it in '2'..'9' }
        val letters = code.length in 2..30 && code.all { it in 'a'..'z' }
        if (!numeric && !letters) return CandidateSelection(
            native.mapIndexed { index, text -> RankedCandidate(text, nativeIndex = index) }, native.size,
            blockedTexts = blocked,
        )
        val history = try { store?.relatedLearned(code).orEmpty() } catch (_: Exception) { emptyList() }
        val personalWords = (try { store?.personalWords(code).orEmpty() } catch (_: Exception) { emptyList() })
            .filter { constraint == null || constraint.matches(it.pinyin) }
        timing?.set(1, SystemClock.uptimeMillis())
        val publicCompletions = inputCompletions?.query(code).orEmpty().filter { constraint == null || constraint.matches(it.pinyin) }
        timing?.set(2, SystemClock.uptimeMillis())
        val retainedTexts = history.mapTo(hashSetOf()) { it.choice.text }.apply { addAll(native) }
        val mainDictionary = lexicon
        val domainDictionary = domains
        val rejected = mutableSetOf<String>()
        val accepted = mutableSetOf<String>()
        fun lookup(dictionary: T9Lexicon?): List<T9Candidate> = if (numeric) {
            // 多个高频同码词不能把完整日常词裁掉；仍是固定上限，学习词可越过上限召回。
            dictionary?.query(code, limit = 32, includeTexts = retainedTexts,
                acceptsReading = constraint?.let { locked -> { reading -> locked.matches(reading) } }) { text, allowed ->
                if (allowed) accepted.add(text) else rejected.add(text)
            }.orEmpty()
        } else dictionary?.queryPinyin(code).orEmpty()
        val dictionaryWords = lookup(mainDictionary)
        val domainWords = lookup(domainDictionary)
        timing?.set(3, SystemClock.uptimeMillis())
        val normalKeys = (dictionaryWords + domainWords).mapTo(hashSetOf()) { it.text to it.pinyin }
        val hasNativeSingle = numeric && native.indices.any { index ->
            native[index].codePointCount(0, native[index].length) == 1 &&
                T9Lexicon.digits(nativeComments?.getOrNull(index).orEmpty().replace("'", "").replace(" ", "")) == code
        }
        val completions = publicCompletions.filter {
            !hasNativeSingle || InputSpellingMatch.match(code, it.pinyin)?.kind != InputMatchKind.INITIAL_FULL_WORD
        }.filter {
            (it.text to it.pinyin) !in normalKeys ||
                (numeric && T9Spelling.preedit(code, it.pinyin)?.none(Char::isDigit) != true)
        }
        val shortWords = if (constraint == null && numeric && publicCompletions.isEmpty()) {
            (mainDictionary?.queryShortWords(code, includeTexts = retainedTexts).orEmpty() +
                domainDictionary?.queryShortWords(code, includeTexts = retainedTexts).orEmpty())
                .filter { it.text to it.pinyin !in normalKeys }
                .filter { !hasNativeSingle || InputSpellingMatch.match(code, it.pinyin)?.kind != InputMatchKind.INITIAL_FULL_WORD }
        } else emptyList()
        // 仅修补旧规则没有完整汉字候选的空缺，正常整词不增加混拼查询与候选。
        val needsInitialFull = constraint == null && numeric && code.length in 4..7 && dictionaryWords.isEmpty() && domainWords.isEmpty() &&
            personalWords.isEmpty() && completions.isEmpty() && shortWords.isEmpty() && native.indices.none { index ->
                native[index].any { Character.isIdeographic(it.code) } &&
                    (nativeComments == null || T9Spelling.preedit(code, nativeComments.getOrNull(index).orEmpty())
                        ?.none(Char::isDigit) == true)
            }
        val initialFullWords = if (needsInitialFull) (mainDictionary?.queryInitialFull(code, includeTexts = retainedTexts).orEmpty() +
            domainDictionary?.queryInitialFull(code, includeTexts = retainedTexts).orEmpty()).sortedByDescending { it.frequency }
        else emptyList()
        fun standardWord(word: T9Candidate): Boolean = InputSpellingMatch.match(code, word.pinyin) != null ||
            (if (numeric) T9Lexicon.digits(word.pinyin.replace(" ", "")) else word.pinyin.replace(" ", "")) == code
        val needsCorrection = constraint == null && code.length >= 6 && dictionaryWords.isEmpty() && domainWords.isEmpty() &&
            personalWords.isEmpty() && completions.none(::standardWord) && shortWords.none(::standardWord) && initialFullWords.isEmpty() && native.indices.none { index ->
                val reading = nativeComments?.getOrNull(index).orEmpty()
                if (numeric) T9Spelling.preedit(code, reading)?.none(Char::isDigit) == true
                else reading.replace(" ", "").replace("'", "") == code
            }
        val corrections = if (needsCorrection) {
            (mainDictionary?.queryCorrections(code, allowEarlyTypo = letters).orEmpty() +
                domainDictionary?.queryCorrections(code, allowEarlyTypo = letters).orEmpty())
                .sortedByDescending { it.frequency }.distinctBy { it.text }.take(2)
        } else emptyList()
        val correctionKeys = corrections.mapTo(hashSetOf()) { it.text to it.pinyin }
        val extraWords = completions + personalWords + initialFullWords + shortWords + corrections
        val readingMemo = CandidateReadingMemo()
        val extraReadings = extraWords.mapNotNull { word ->
            (if ((word.text to word.pinyin) in correctionKeys) InputSpellingMatch(InputMatchKind.TYPO_CORRECTION, code, code)
            else InputSpellingMatch.match(code, word.pinyin) ?: InputSpellingMatch.shortWord(code, word.pinyin))?.let { (word.text to word.pinyin) to it }
        }.toMap()
        timing?.set(4, SystemClock.uptimeMillis())
        fun extraMatch(text: String, reading: String): InputSpellingMatch? {
            val normalized = readingMemo.normalize(text, reading).orEmpty()
            extraReadings[text to normalized]?.let { return it }
            if (constraint != null || text.length !in 2..3 || normalized.isEmpty() || (text to normalized) in normalKeys) return null
            val ordinary = if (numeric) T9Spelling.allows(code, text, normalized)
                else normalized.replace(" ", "").startsWith(code)
            if (ordinary || (mainDictionary?.containsText(text) != true && domainDictionary?.containsText(text) != true &&
                    publicPhrases?.contains(text) != true && personalWords.none { it.text == text && it.pinyin == normalized })) return null
            return InputSpellingMatch.match(code, normalized) ?: InputSpellingMatch.shortWord(code, normalized)
        }
        val selectedByText = history.filter { it.choice.count > 0 }.groupBy { it.choice.text }
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
            if (personalWords.any { it.text == text && readingMemo.normalize(text, reading) == it.pinyin }) return true
            val codes = T9Spelling.completionCodes(reading, minLength = 3)
            return selectedByText[text].orEmpty().any { record ->
                record.code == code || (code in codes && record.code in codes)
            }
        }
        fun trusted(text: String, reading: String): Boolean =
            locallyTrusted(text, reading) || publicPhrases?.contains(text) == true || extraMatch(text, reading) != null
        val allLocalReadings = dictionaryWords + domainWords + personalWords + initialFullWords
        extraWords.forEach { accepted.add(it.text) }
        val common = if (numeric) allLocalReadings.groupBy { it.text }.values
            .map { readings -> readings.maxBy { it.frequency } }.sortedByDescending { it.frequency }
        else allLocalReadings.distinctBy { it.text }
        // 原生读音直接校验，不能依赖本地词库是否收录该词。
        val nativeAllowed = native.indices.map { index ->
            matchesConstraint(native[index], nativeComments?.getOrNull(index).orEmpty()) &&
                (nativeComments == null || T9Spelling.allows(code, native[index], nativeComments.getOrNull(index).orEmpty()) ||
                    extraMatch(native[index], nativeComments.getOrNull(index).orEmpty()) != null)
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
            return count == reading.trim().split(readingSeparators).size
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
        val extras = extraWords.distinctBy { it.text to it.pinyin }.mapNotNull { word ->
            extraMatch(word.text, word.pinyin)?.let { RankedCandidate(word.text, word.pinyin, inputMatch = it) }
        }
        val shortExtras = extras.filter { it.inputMatch?.kind == InputMatchKind.SHORT_WORD }
        fun candidateReading(candidate: RankedCandidate): String = readingMemo.normalize(candidate.text,
            candidate.pinyin.ifBlank { candidate.nativeIndex?.let { nativeComments?.getOrNull(it) }.orEmpty() }).orEmpty()
        fun fullCode(candidate: RankedCandidate): String {
            val reading = candidateReading(candidate).replace(" ", "")
            return if (numeric) T9Lexicon.digits(reading) else reading
        }
        val (exactShort, abbreviatedShort) = shortExtras.partition { fullCode(it) == code }
        val exactEnd = base.indexOfLast { fullCode(it) == code } + 1
        val withExactShort = base.take(exactEnd) + exactShort + base.drop(exactEnd)
        val normalEnd = withExactShort.indexOfLast {
            if (numeric) T9Spelling.preedit(code, it.pinyin)?.none(Char::isDigit) == true
            else (it.nativeIndex != null && (nativeComments == null || it.text.equals(code, ignoreCase = true))) ||
                candidateReading(it).replace(" ", "").startsWith(code)
        } + 1
        val withShortWords = if (letters && normalEnd == 0 && normalKeys.isNotEmpty()) {
            // 全键已有简拼排序可用；新词只补充。仅同源词频强于全部现有词时借一个槽位。
            val existingFrequency = publicCompletions.filter { (it.text to it.pinyin) in normalKeys }.maxOfOrNull { it.frequency }
            val suggestedFrequency = abbreviatedShort.firstOrNull()?.let { suggestion ->
                publicCompletions.firstOrNull { it.text == suggestion.text && it.pinyin == suggestion.pinyin }?.frequency
            }
            if (existingFrequency != null && suggestedFrequency != null && suggestedFrequency > existingFrequency) {
                withExactShort.take(3) + abbreviatedShort.take(1) + withExactShort.drop(3) + abbreviatedShort.drop(1)
            } else withExactShort + abbreviatedShort
        } else withExactShort.take(normalEnd) + abbreviatedShort + withExactShort.drop(normalEnd)
        val initials = extras.filter { it.inputMatch?.kind == InputMatchKind.THREE_INITIALS }
        val phrases = extras.filter { it.inputMatch?.kind == InputMatchKind.PHRASE_PREFIX }
            .distinctBy { it.text }
        val wholePhrases = extras.filter { it.inputMatch?.kind == InputMatchKind.WHOLE_PHRASE }
        val corrected = extras.filter { it.inputMatch?.kind == InputMatchKind.TYPO_CORRECTION }
        val withWhole = wholePhrases + if (completions.isNotEmpty())
            withShortWords.take(1) + corrected + withShortWords.drop(1) else corrected + withShortWords
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
        val initialFull = extras.filter { it.inputMatch?.kind == InputMatchKind.INITIAL_FULL_WORD }.toMutableList()
        if (initialFull.isNotEmpty()) {
            val existing = (mainDictionary?.queryInitialFull(code, includeTexts = retainedTexts).orEmpty() +
                domainDictionary?.queryInitialFull(code, includeTexts = retainedTexts).orEmpty())
                .groupBy { it.text to it.pinyin }.mapValues { (_, words) -> words.maxOf { it.frequency } }
            val slots = initialFull.indices.filter { initialFull[it].text to initialFull[it].pinyin in existing }
            val ordered = slots.map { initialFull[it] }.sortedByDescending { existing[it.text to it.pinyin] }
            slots.forEachIndexed { index, slot -> initialFull[slot] = ordered[index] }
        }
        // 已有完整候选和末字补全仍在前；仅表情或分段前缀不能挤掉完整双字词。
        val withInitialFull = if (initialFull.isEmpty()) withInitials else {
            val insertion = withInitials.indexOfLast {
                it.inputMatch?.kind != InputMatchKind.INITIAL_FULL_WORD &&
                    (it.inputMatch != null || (it.text.any { char -> Character.isIdeographic(char.code) } &&
                        T9Spelling.preedit(code, it.pinyin)?.none(Char::isDigit) == true))
            } + 1
            withInitials.take(insertion) + initialFull + withInitials.drop(insertion)
        }
        val combined = if (phrases.isEmpty()) withInitialFull else withInitialFull.take(1) + phrases + withInitialFull.drop(1)
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
        val preferredKeys = preferred.mapTo(hashSetOf()) { it.text to it.pinyin }
        val personalKeys = personalWords.mapTo(hashSetOf()) { it.text to it.pinyin }
        timing?.set(5, SystemClock.uptimeMillis())
        val annotated = (preferred.map {
            RankedCandidate(it.text, it.pinyin, inputMatch = extraMatch(it.text, it.pinyin))
        } + combined).map { candidate ->
            val reading = readingMemo.normalize(candidate.text, candidate.pinyin)
            val evidence = when {
                (candidate.text to reading) in preferredKeys -> "personal_preferred"
                mainDictionary?.containsText(candidate.text) == true || domainDictionary?.containsText(candidate.text) == true -> "dictionary"
                publicPhrases?.contains(candidate.text) == true -> "public_phrase"
                (candidate.text to reading) in personalKeys -> "personal"
                candidate.nativeIndex != null -> "native_unverified"
                else -> "unknown"
            }
            candidate.copy(lexicalEvidence = evidence, wholeInput = numeric && code.length >= 4 &&
                candidate.text.codePointCount(0, candidate.text.length) > 1 &&
                code in T9Spelling.completionCodes(candidate.pinyin))
        }
        // 同一匹配层级内，词典候选共享公共词频顺序，避免原生来源占满补全首屏。
        // 完整拼写和末字补全仍分开，手工词、分段前缀和未知整句不参与词频替换。
        val publicFrequencies = (dictionaryWords + domainWords).groupBy { it.text to it.pinyin }
            .mapValues { (_, words) -> words.maxOf { it.frequency } }
        val corpusFrequencies = publicCompletions.associate { (it.text to it.pinyin) to it.frequency }
        val candidateFrequencies = annotated.map { publicFrequencies[it.text to readingMemo.normalize(it.text, it.pinyin)] }
        // 原生首选可能包含 Rime 自身学习；应用层没有其来源证据，保留合法首选的基础槽位。
        val nativePreferredText = annotated.firstOrNull {
            it.nativeIndex == 0 && it.wholeInput && it.lexicalEvidence == "dictionary"
        }?.text
        val frequencyRanked = annotated.toMutableList()
        for (exact in listOf(true, false)) {
            val slots = annotated.indices.filter { index ->
                val candidate = annotated[index]
                (nativeComments != null || native.isEmpty()) && candidate.text != nativePreferredText &&
                    candidate.wholeInput && candidate.lexicalEvidence == "dictionary" &&
                    (T9Lexicon.digits(candidate.pinyin.replace(" ", "").replace("'", "")) == code) == exact &&
                    candidateFrequencies[index] != null
            }
            val ordered = slots.sortedByDescending { candidateFrequencies[it] }.map { annotated[it] }
            slots.forEachIndexed { index, slot -> frequencyRanked[slot] = ordered[index] }
            // 只在该公开来源均有读音/词频的槽位内重排，其他来源的位置保持，不相加词频。
            val sharedSlots = slots.filter { index ->
                val candidate = frequencyRanked[index]
                native.isNotEmpty() && corpusFrequencies[candidate.text to readingMemo.normalize(candidate.text, candidate.pinyin)] != null
            }
            val sharedOrder = sharedSlots.map { frequencyRanked[it] }.sortedByDescending {
                publicFrequencyBand(corpusFrequencies[it.text to readingMemo.normalize(it.text, it.pinyin)] ?: 0)
            }
            sharedSlots.forEachIndexed { index, slot -> frequencyRanked[slot] = sharedOrder[index] }
        }
        var phraseCount = 0
        val ranked = PersonalCandidateRanker.rank(frequencyRanked, learned, now, trace = true) {
            correctionHints.at(code, it, now)
        }.filter {
            (constraint == null || if (it.nativeIndex != null) matchesConstraint(it.text, it.pinyin) else constraint.matches(it.pinyin)) &&
                (it.inputMatch?.kind != InputMatchKind.PHRASE_PREFIX || ++phraseCount <= 2)
        }
        if (timing != null) {
            timing[6] = SystemClock.uptimeMillis()
            if (timing[6] - timing[0] >= 32) Log.d("ImeLatencyDetail", "store_ms=${timing[1]-timing[0]} index_ms=${timing[2]-timing[1]} lexicon_ms=${timing[3]-timing[2]} match_ms=${timing[4]-timing[3]} combine_ms=${timing[5]-timing[4]} rank_ms=${timing[6]-timing[5]}")
        }
        return CandidateSelection(ranked, native.size, rejected.toSet(), ::extraMatch, blocked) { text, reading ->
            matchesConstraint(text, reading) && (trusted(text, reading) || nativeWhole(text, reading))
        }
    }

    // 公开权重含人工调频，固定截取两位有效数字；同层稳定保留原词典次序。
    private fun publicFrequencyBand(frequency: Long): Long {
        if (frequency <= 0) return 0
        var scale = 1L
        while (frequency / scale >= 100) scale *= 10
        return frequency / scale * scale
    }

    /** 锁音/分段只重排原生现有项，额外排除明确禁词；不注入或合并不同读音的索引。 */
    fun rankNative(native: List<RankedCandidate>, nativeCount: Int): CandidateSelection {
        val readings = native.map { PersonalWordReading.normalize(it.text, it.pinyin) }
        val codes = readings.map { it?.let { reading -> T9Lexicon.digits(reading.replace(" ", "")) } }
        val now = System.currentTimeMillis()
        val history = codes.filterNotNull().distinct().associateWith { code ->
            try { store?.learned(code).orEmpty().associateBy { it.text } }
            catch (_: Exception) { emptyMap() }
        }
        val ranked = native.mapIndexed { index, candidate ->
            val choice = history[codes[index]]?.get(candidate.text)?.takeIf { it.count > 0 }
            PersonalCandidateRanker.score(index, choice?.weight ?: 0.0,
                choice?.lastUsed ?: now, choice?.lastUsed, now,
                codes[index]?.let { correctionHints.at(it, candidate.text, now) }) to candidate
        }.sortedByDescending { it.first }.map { it.second }
        return CandidateSelection(ranked, nativeCount, blockedTexts = blockedCandidateTexts())
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
    fun learnTemporarily(selection: T9CommitSelection, id: String, confirmedCorrection: Boolean = false): String? {
        val current = store ?: return null
        return try {
            val upload = appContext?.let { CollectionConsent.enabled(it) } == true &&
                CollectionConsent.allowsEditor(YuyanEmojiCompat.mEditorInfo) && CollectionConsent.allowsText(selection.text)
            current.stageLearning(id, learningChoices(selection), if (upload) ServerConfig.eventTargets else emptyList())
            scheduleLearningSettlement()
            if (confirmedCorrection) correctionHints.record(id, selection.code, selection.text, System.currentTimeMillis())
            id
        } catch (error: Exception) {
            Log.w("OfflineT9", "临时学习保存失败", error)
            null
        }
    }

    fun cancelLearning(id: String) {
        try { if (store?.cancelLearning(id) == true) correctionHints.cancel(id) }
        catch (error: Exception) { Log.w("OfflineT9", "临时学习撤销失败", error) }
    }

    fun correctLearning(correction: LearningCorrection): Boolean = try {
        val retained = correction.retainedParts.map { part ->
            PendingChoice(T9Lexicon.digits(part.pinyin.replace(" ", "")), part.text, part.pinyin)
        }
        (store?.restrictLearning(correction.rewardId, retained) == true).also { applied ->
            if (applied) correctionHints.cancel(correction.rewardId)
        }
    } catch (error: Exception) {
        Log.w("OfflineT9", "局部学习撤销失败", error)
        false
    }

    private fun scheduleLearningSettlement() {
        val current = store ?: return
        val context = appContext ?: return
        // 不在主线程结算，也不持有候选 helper 的监视器等待其他写者。
        // 捕获实例避免旧会话定时器触碰重建后的数据库；事务负责重复调用幂等。
        learningScope.launch {
            delay(CorrectionLearningTracker.REWARD_WINDOW_MS + 1)
            if (store === current) {
                try {
                    LocalInputStore(context, current.databaseName).use { it.settleLearning() }
                    if (appContext?.let { CollectionConsent.enabled(it) } == true) DataCollector.requestSync()
                } catch (error: Exception) { Log.w("OfflineT9", "临时学习结算失败，下次重试", error) }
            }
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
