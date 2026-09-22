package com.yuyan.inputmethod

import android.view.KeyEvent
import com.yuyan.imemodule.application.CustomConstant
import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.data.completion.OfflineT9Candidates
import com.yuyan.imemodule.data.completion.CandidateSelection
import com.yuyan.imemodule.data.completion.RankedCandidate
import com.yuyan.imemodule.data.completion.T9CommitTracker
import com.yuyan.imemodule.data.completion.CompletionSync
import com.yuyan.imemodule.data.completion.OfflineAssociationCompletion
import com.yuyan.imemodule.manager.InputModeSwitcher
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.utils.StringUtils
import com.yuyan.inputmethod.core.CandidateListItem
import com.yuyan.inputmethod.core.Rime
import com.yuyan.inputmethod.data.InputKey
import com.yuyan.inputmethod.data.KeyRecordStack
import com.yuyan.inputmethod.util.DoublePinYinUtils
import com.yuyan.inputmethod.util.LX17PinYinUtils
import com.yuyan.inputmethod.util.QwertyPinYinUtils
import com.yuyan.inputmethod.util.T9Spelling
import com.yuyan.inputmethod.util.T9PinYinUtils
import java.util.Locale

object RimeEngine {
    private val keyRecordStack = KeyRecordStack()
    private var personalCandidates: CandidateSelection? = null
    private var nativeCandidateMetadata = CandidateSelection(emptyList(), 0)
    internal fun candidateForSelection(index: Int) = personalCandidates?.at(index - customPhraseSize)
        ?: nativeCandidateMetadata.at(index - customPhraseSize)
    private val t9CommitTracker = T9CommitTracker()

    private fun learningCode(forCommit: Boolean = false): String {
        if (InputModeSwitcher.isEnglish || AppPrefs.getInstance().input.chineseFanTi.getValue()) return ""
        return when (Rime.getCurrentRimeSchema()) {
            CustomConstant.SCHEMA_ZH_T9 -> if (forCommit) keyRecordStack.compositionT9Digits() else keyRecordStack.unlockedT9Digits()
            CustomConstant.SCHEMA_ZH_QWERTY -> keyRecordStack.unlockedPinyin()
            else -> ""
        }
    }

    fun takeT9CommitCode(text: String): String? = t9CommitTracker.consume(text, true)
    internal fun takeT9CommitSelection(text: String) = t9CommitTracker.consumeSelection(text, true)
    private var pinyins: Array<String> = emptyArray() // 候选词界面的候选拼音列表
    var showCandidates: List<CandidateListItem> = emptyList() // 所有待展示的候选词
    var showComposition: String = "" // 候选词上方展示的拼音
    /** 只读显示：用当前原生元数据补全可见拼音，不改供提交/回车门禁使用的showComposition。 */
    internal fun getT9CompositionForDisplay(): String = T9Spelling.fullDisplayComposition(
        keyRecordStack.unlockedT9Digits(), showComposition,
        nativeCandidateMetadata.firstPage.map { it.pinyin },
    )

    var preCommitText: String = "" // 待提交的文字
    private var customPhraseSize: Int = 0 // 自定义引擎候选词长度
    private var associationRimeIndexes: List<Int?> = emptyList()
    const val MASK_CASE_LOWER = 0
    private var charCase = 0x0000
    fun init() {
        Rime.getInstance(false)
    }

    fun selectSchema(mod: String): Boolean {
        keyRecordStack.clear()
        charCase = MASK_CASE_LOWER
        Rime.startup(Launcher.instance.context, false)
        Rime.clearComposition()
        clearCachedCompositionForSchemaSwitch()
        return Rime.selectSchema(mod).also {
            clearCachedCompositionForSchemaSwitch()
        }
    }

    internal fun clearCachedCompositionForSchemaSwitch() {
        personalCandidates = null
        nativeCandidateMetadata = CandidateSelection(emptyList(), 0)
        t9CommitTracker.clear()
        showCandidates = emptyList()
        showComposition = ""
        preCommitText = ""
        pinyins = emptyArray()
        associationRimeIndexes = emptyList()
        customPhraseSize = 0
    }

    fun getCurrentRimeSchema(): String {
        return Rime.getCurrentRimeSchema()
    }

    /**
     * 是否输入完毕
     */
    fun isFinish(): Boolean {
        return keyRecordStack.isEmpty()
    }

    fun onNormalKey(event: KeyEvent) {
        t9CommitTracker.clear()
        val keyCode = event.keyCode
        val keyChar = if(keyCode == KeyEvent.KEYCODE_APOSTROPHE) if(isFinish()) '/'.code else '\''.code
            else event.unicodeChar
        if (keyRecordStack.pushKey(event))Rime.processKey(keyChar, event.action)
        updateCandidatesOrCommitText()
    }

    fun onDeleteKey() {
        t9CommitTracker.clear()
        processDelAction()
        updateCandidatesOrCommitText()
    }

    fun selectCandidate(index: Int): String? {
        val code = learningCode(forCommit = true)
        val selected = candidateForSelection(index)
        if (selected != null && selected.nativeIndex == null) {
            reset()
            preCommitText = selected.text
            t9CommitTracker.selected(code, selected.text, selected.pinyin)
            return preCommitText
        }
        val indexReal = selected?.nativeIndex ?: (index - customPhraseSize)
        if (indexReal < 0) return null
        val chosenText = selected?.text?.takeIf { it.isNotEmpty() } ?: showCandidates.getOrNull(index)?.text.orEmpty()
        val chosenReading = selected?.pinyin?.takeIf { it.isNotEmpty() } ?: showCandidates.getOrNull(index)?.comment.orEmpty()
        Rime.selectCandidate(indexReal)
        keyRecordStack.pushCandidateSelectAction()
        val committed = updateCandidatesOrCommitText()
        t9CommitTracker.segment(code, chosenText, chosenReading, committed)
        return committed
    }

    fun getNextPageCandidates(): Array<CandidateListItem> {
        while (Rime.hasRight()) {
            Rime.processKey(getRimeKeycodeByName("Page_Down"), 0)
           val candidates = Rime.getRimeContext()!!.candidates
            when (charCase) {
                KeyEvent.META_SHIFT_ON -> {
                    for (item in candidates) {
                        item.text = item.text.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                    }
                }
                KeyEvent.META_CAPS_LOCK_ON -> {
                    for (item in candidates) {
                        item.text = item.text.uppercase()
                    }
                }
                else -> {
                    for (item in candidates) {
                        item.text = item.text.lowercase()
                    }
                }
            }
            nativeCandidateMetadata.appendNativePage(candidates.map { it.text }, "", candidates.map { it.comment })
            val visible = personalCandidates?.appendNativePage(candidates.map { it.text }, learningCode(), candidates.map { it.comment })
                ?.map { candidates[it] }?.toTypedArray() ?: candidates
            if (visible.isNotEmpty()) return visible
            // 过滤后整页为空时继续翻页，不能让候选栏误以为已经没有后续候选。
        }
        return emptyArray()
    }

    fun selectPinyin(index: Int) {
        // 锁音不改原始按键；保留明确选中的段，最终仍须整词/读音/编码三者一致。
        val pinyinKey = keyRecordStack.pushPinyinSelectAction(pinyins[index]) ?: return
        Rime.replaceKey(pinyinKey.posInInput, pinyinKey.t9Keys().length, pinyinKey.pinyin())
        updateCandidatesOrCommitText()
    }

    fun predictAssociationWords(text: String) {
        personalCandidates = null
        nativeCandidateMetadata = CandidateSelection(emptyList(), 0)
        pinyins = emptyArray()
        if (text.isNotEmpty()) {
            val merged = AssociationCandidateMerger.merge(
                offline = OfflineAssociationCompletion.query(text),
                rime = Rime.getAssociateList(text).filterNotNull(),
                custom = CustomEngine.predictAssociationWordsChinese(text),
                remote = CompletionSync.query(text).map { it.completion },
            )
            associationRimeIndexes = merged.map { it.rimeIndex }
            showCandidates = merged.map {
                val comment = if (it.source == AssociationCandidateSource.REMOTE) CompletionSync.candidateComment else ""
                CandidateListItem(comment, it.text)
            }
            showComposition = ""
        }
    }

    fun selectAssociation(index: Int) {
        val selected = showCandidates.getOrNull(index) ?: return
        associationRimeIndexes.getOrNull(index)?.let(Rime::chooseAssociate)
        preCommitText = selected.text
        showCandidates = emptyList()
        associationRimeIndexes = emptyList()
    }

    fun reset() {
        personalCandidates = null
        nativeCandidateMetadata = CandidateSelection(emptyList(), 0)
        t9CommitTracker.clear()
        showCandidates = emptyList()
        pinyins = emptyArray()
        showComposition = ""
        preCommitText = ""
        associationRimeIndexes = emptyList()
        keyRecordStack.clear()
        Rime.clearComposition()
        if(charCase == KeyEvent.META_SHIFT_ON) charCase = MASK_CASE_LOWER
    }

    fun destroy() = Rime.destroy()

    fun processDelAction() {
        when (val lastKey = keyRecordStack.pop()) {
            is InputKey.PinyinKey -> {
                val pinyinKey = keyRecordStack.restorePinyinToT9Key(lastKey) ?: return
                replacePinyinWithT9Keys(pinyinKey)
            }
            InputKey.SelectPinyinAction -> {
                val pinyinKey = keyRecordStack.restorePinyinToT9Key() ?: return
                replacePinyinWithT9Keys(pinyinKey)
            }
            is InputKey.Apostrophe -> {
                if (!lastKey.dummy) {
                    Rime.processKey(getRimeKeycodeByName("BackSpace"), 0)
                }
            }
            else -> {
                Rime.processKey(getRimeKeycodeByName("BackSpace"), 0)
            }
        }
    }

    private fun replacePinyinWithT9Keys(pinyinKey: InputKey.PinyinKey) {
        /**
         * 当前输入状态是“你h”时，引擎默认删除行为是“ni”（删除h并且删除“你”的选中状态）
         * 可能存在引擎操作栈与记录的操作栈不一样的问题
         * 临时方案，尝试不同长度的替换，至少保证可以把拼音回退成9键
         */
        if (!Rime.replaceKey(pinyinKey.posInInput, pinyinKey.inputKeyLength, pinyinKey.t9Keys())) {
            Rime.replaceKey(pinyinKey.posInInput, pinyinKey.pinyinLength, pinyinKey.t9Keys())
        }
    }

    private fun updateCandidatesOrCommitText(): String? {
        val rimeCommit = Rime.getRimeCommit()
        if (rimeCommit != null) {
            keyRecordStack.clear()
            personalCandidates = null
            nativeCandidateMetadata = CandidateSelection(emptyList(), 0)
            preCommitText = rimeCommit.commitText
            preCommitText = if (charCase == KeyEvent.META_SHIFT_ON) {
                preCommitText.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            } else if (charCase == KeyEvent.META_CAPS_LOCK_ON) {
                preCommitText.uppercase()
            } else {
                preCommitText.lowercase()
            }
            showComposition = ""
            showCandidates = emptyList()
            return preCommitText
        }
        val candidates = Rime.getRimeContext()?.candidates?.asList() ?: emptyList()
        nativeCandidateMetadata = CandidateSelection(candidates.mapIndexed { index, item ->
            RankedCandidate(item.text, item.comment, index)
        }, candidates.size)
        customPhraseSize = 0
        val compositionText = Rime.compositionText
        showCandidates = when {
            compositionText.isNotBlank() -> {
                val phrase = CustomEngine.processPhrase(compositionText.replace("\'", ""))
                if(InputModeSwitcher.isEnglish && StringUtils.isLetter(compositionText) &&
                    !compositionText.equals(candidates.first().text, ignoreCase = true) ){
                    phrase.add(0, compositionText)
                }
                customPhraseSize = phrase.size
                phrase.map { content -> CandidateListItem("📋", content) }.toMutableList().plus(candidates)
            }
            else -> candidates
        }
        var count = Rime.compositionText.count { it in 'A'..'Z' }
        if (count > 0) {
            keyRecordStack.forEachReversed { inputKey ->
                if (inputKey is InputKey.T9Key) inputKey.consumed = count-- <= 0
            }
        }
        var composition = getCurrentComposition(candidates)
        when (charCase) {
            KeyEvent.META_SHIFT_ON -> {
                for (item in showCandidates) item.text = item.text.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                composition = composition.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            }
            KeyEvent.META_CAPS_LOCK_ON -> {
                for (item in showCandidates) item.text = item.text.uppercase()
                composition = composition.uppercase()
            }
            else -> {
                for (item in showCandidates) item.text = item.text.lowercase()
                composition = composition.lowercase()
            }
        }
        val rimeSchema = Rime.getCurrentRimeSchema()
        val code = learningCode()
        pinyins = when (rimeSchema) {
            CustomConstant.SCHEMA_ZH_T9 -> {
                T9PinYinUtils.t9KeyToPinyin(if (code.isNotEmpty()) code.map { "ADGJMPTW"[it - '2'] }.joinToString("") else compositionText)
            }
            CustomConstant.SCHEMA_ZH_DOUBLE_LX17 -> {
                LX17PinYinUtils.lx17KeyToPinyin(compositionText.split('\'').firstOrNull { part -> part.isNotEmpty() && part.all { it.isUpperCase() } } ?: "")
            }
            else -> {
                emptyArray()
            }
        }
        personalCandidates = when {
            code.isNotEmpty() -> OfflineT9Candidates.select(code, candidates.map { it.text }, candidates.map { it.comment })
            rimeSchema == CustomConstant.SCHEMA_ZH_T9 && !keyRecordStack.isEmpty() &&
                !InputModeSwitcher.isEnglish && !AppPrefs.getInstance().input.chineseFanTi.getValue() ->
                OfflineT9Candidates.rankNative(nativeCandidateMetadata.firstPage, candidates.size)
            else -> null
        }
        personalCandidates?.let { selection ->
            showCandidates = showCandidates.take(customPhraseSize) + selection.firstPage.map {
                it.nativeIndex?.let { index -> candidates[index] } ?: CandidateListItem("本地", it.text)
            }.ifEmpty {
                getNextPageCandidates().asList()
            }
            val first = selection.firstPage.firstOrNull()
            if (code.isNotEmpty()) {
                composition = if (rimeSchema == CustomConstant.SCHEMA_ZH_T9) {
                    val reading = first?.pinyin?.takeIf { it.isNotBlank() }
                        ?: showCandidates.drop(customPhraseSize).firstOrNull()?.comment.orEmpty()
                    T9Spelling.preedit(code, reading) ?: code
                } else first?.pinyin?.takeIf { it.isNotBlank() }?.replace(' ', '\'') ?: composition
            } else {
                // 保留原生已选前缀，余段读音跟随个人重排后的首项。
                composition = getCurrentComposition(showCandidates.drop(customPhraseSize))
            }
        }
        showComposition = composition
        preCommitText = ""
        return null
    }

    /**
     * 拿到候选词拼音组合
     */
    fun getPrefixs(): Array<String> {
        return pinyins
    }

    private fun getCurrentComposition(candidates: List<CandidateListItem>): String {
        val composition = Rime.compositionText
        val rimeSchema = Rime.getCurrentRimeSchema()
        if(rimeSchema == CustomConstant.SCHEMA_EN) return ""
        if(composition.isEmpty()) return ""
        if(candidates.isEmpty()) return composition
        val comment = candidates.first().comment
        val result =  when {
            comment.isNotBlank() && comment.startsWith("~") -> composition
            rimeSchema == CustomConstant.SCHEMA_ZH_T9 -> {
                T9PinYinUtils.getT9Composition(composition, comment)
            }
            rimeSchema.startsWith(CustomConstant.SCHEMA_ZH_DOUBLE_FLYPY) -> {
                if(!AppPrefs.getInstance().keyboardSetting.keyboardDoubleInputKey.getValue()) composition
                else DoublePinYinUtils.getDoublePinYinComposition(rimeSchema, composition, comment)
            }
            else -> {
                QwertyPinYinUtils.getQwertyComposition(composition, comment)
            }
        }
        return if (!composition.endsWith("'") && result.endsWith("'")) result.dropLast(1) else result
    }

    /**
     * 设置输入法搜索参数
     */
    fun setImeOption(option: String, value: Boolean) {
        Rime.setOption(option, value)
    }

    /**
     * 获取Rime定义键值
     */
    private fun getRimeKeycodeByName(name: String) : Int {
        return Rime.getRimeKeycodeByName(name)
    }

    fun setCharCase(charCase: Int) {
        this.charCase = charCase
    }

}
