package com.yuyan.imemodule.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.Toast
import com.yuyan.imemodule.R
import com.yuyan.imemodule.candidate.CandidateView
import com.yuyan.imemodule.data.completion.OfflineT9Candidates
import com.yuyan.imemodule.data.collect.CollectionConsent
import com.yuyan.imemodule.data.completion.canLearnInput
import com.yuyan.inputmethod.RimeEngine
import com.yuyan.imemodule.data.collect.CommittedEditTracker
import com.yuyan.imemodule.data.collect.committedSnapshot
import com.yuyan.imemodule.data.collect.DataCollector
import com.yuyan.imemodule.data.capture.OutgoingVoiceCapture
import com.yuyan.imemodule.service.capture.ForegroundChatCaptureBridge
import com.yuyan.imemodule.service.capture.ForegroundChatCaptureRequest
import com.yuyan.imemodule.service.capture.isForegroundChatCapturePackage
import com.yuyan.imemodule.data.emojicon.YuyanEmojiCompat
import com.yuyan.imemodule.data.theme.Theme
import com.yuyan.imemodule.data.theme.ThemeManager.OnThemeChangeListener
import com.yuyan.imemodule.data.theme.ThemeManager.addOnChangedListener
import com.yuyan.imemodule.data.theme.ThemeManager.onSystemDarkModeChange
import com.yuyan.imemodule.data.theme.ThemeManager.removeOnChangedListener
import com.yuyan.imemodule.expression.ExpressionCommitKind
import com.yuyan.imemodule.expression.HostTextCommitDispatcher
import com.yuyan.imemodule.keyboard.InputView
import com.yuyan.imemodule.keyboard.KeyboardManager
import com.yuyan.imemodule.keyboard.container.ClipBoardContainer
import com.yuyan.imemodule.prefs.AppPrefs.Companion.getInstance
import com.yuyan.imemodule.prefs.behavior.SkbMenuMode
import com.yuyan.imemodule.singleton.EnvironmentSingleton
import com.yuyan.imemodule.utils.KeyboardLoaderUtil
import com.yuyan.imemodule.utils.StringUtils
import com.yuyan.imemodule.utils.isDarkMode
import com.yuyan.imemodule.view.preference.ManagedPreference
import com.yuyan.imemodule.ui.activity.VoicePermissionActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splitties.bitflags.hasFlag

/**
 * Main class of the Pinyin input method. 输入法服务
 */
open class ImeService : InputMethodService() {
    private val deliverySettingsJob = kotlinx.coroutines.SupervisorJob()
    private val deliverySettingsScope = CoroutineScope(deliverySettingsJob + Dispatchers.IO)
    private var isHardwareKeyboard = false
    private var isSoftKeyboard = false
    private lateinit var mInputView: InputView
    private lateinit var mCandidateView: CandidateView
    private var expressionBackHandled = false
    private var expressionBackCallback: Any? = null
    private val expressionBackCallbackController = ExpressionBackCallbackController(
        register = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerExpressionBackCallback()
            }
        },
        unregister = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                unregisterExpressionBackCallback()
            }
        },
    )
    private var voiceRecognizer: SpeechRecognizer? = null
    private var voiceRecognizerMode = VoiceRecognizerMode.SYSTEM
    private var voiceFallbackAttempted = false
    private var voiceStopRequested = false
    private var voiceCancelled = false
    private var voiceHasPartialText = false
    private var nextVoiceSessionId = 0L
    private var activeVoiceSession: VoiceInputSession? = null
    private var hostTextCommitListenerOwner: Any? = null
    private var hostTextCommitListener: ((String, ExpressionCommitKind) -> Unit)? = null
    private var hostTextEditListener: (() -> Unit)? = null
    private val committedEdits = CommittedEditTracker()
    private var composingForHistory = false
    private var historyComposingText: String? = null

    private fun clearHistoryComposition() {
        composingForHistory = false
        historyComposingText = null
    }

    private fun historyAllowed(): Boolean =
        CollectionConsent.enabled(this) && CollectionConsent.allowsEditor(YuyanEmojiCompat.mEditorInfo)

    private fun readCommittedText(connection: InputConnection? = currentInputConnection): String? {
        if (!historyAllowed()) { committedEdits.reset(); return null }
        return runCatching {
            committedSnapshot(connection?.getExtractedText(ExtractedTextRequest().apply {
                flags = InputConnection.GET_TEXT_WITH_STYLES
                hintMaxChars = 5001
                hintMaxLines = 1000
            }, 0), composingForHistory || voiceHasPartialText)
        }.getOrNull()
    }

    private fun recordHostEdit(
        before: String?,
        eventType: String,
        fallbackText: String? = null,
        source: String = "keyboard",
        inputCode: String? = null,
        after: String? = readCommittedText(),
    ) {
        if (!historyAllowed() || !CollectionConsent.allowsText(fallbackText) ||
            !CollectionConsent.allowsText(before) || !CollectionConsent.allowsText(after)) {
            committedEdits.reset()
            return
        }
        // 某些宿主会延迟提供更新快照；不能因此丢掉已接受的提交。
        val unchangedCommit = before != null && before == after &&
            eventType in setOf("commit", "voice", "paste") && !fallbackText.isNullOrEmpty()
        val edit = (if (unchangedCommit) committedEdits.record(null, null)
            else committedEdits.record(before, after)) ?: return
        val removed = eventType == "delete" || eventType == "external_delete"
        val text = (if (removed) edit.removedText else edit.insertedText) ?: fallbackText
        val editor = YuyanEmojiCompat.mEditorInfo
        if (!DataCollector.recordEvent(this, eventType, text = text,
                packageName = editor?.packageName, editorId = editor?.fieldId?.toString(),
                source = source, inputCode = inputCode, sessionId = edit.sessionId,
                sequenceNo = edit.sequenceNo, textBefore = edit.before, textAfter = edit.after,
                metadata = buildJsonObject {
                    put("edit_protocol", 1)
                    put("snapshot_complete", edit.complete)
                    put("text_truncated", (text?.length ?: 0) > 5000)
                })) committedEdits.reset()
    }

    /** 宿主菜单/清空等异步变化：只有已跟踪的输入框和可读快照才记录，不读取其他聊天。 */
    private fun observeHostEdit() {
        if (!historyAllowed()) { committedEdits.reset(); return }
        if (composingForHistory || voiceHasPartialText) return
        val before = committedEdits.lastText ?: return
        val after = readCommittedText() ?: run { committedEdits.reset(); return }
        if (before != after) recordHostEdit(before,
            if (after.length < before.length) "external_delete" else "external_insert",
            source = "host_change", after = after)
    }

    internal var hostKeyEventSender: (Int) -> Boolean = ::sendUnmodifiedKeyEventsAndReport
    internal var hostTextCommitter: (String, Int) -> Boolean = { text, newCursorPosition ->
        currentInputConnection?.commitText(
            StringUtils.converted2FlowerTypeface(text),
            newCursorPosition,
        ) == true
    }
    internal var hostEditorActionSender: (Int) -> Boolean = { action ->
        currentInputConnection?.performEditorAction(action) == true
    }
    private val onThemeChangeListener = OnThemeChangeListener { _: Theme? -> if (isHardwareKeyboard) mCandidateView.updateTheme() else mInputView.updateTheme()}
    private val clipboardUpdateContent = getInstance().internal.clipboardUpdateContent
    private val clipboardUpdateContentListener = ManagedPreference.OnChangeListener<String> { _, value ->
        if(isSoftKeyboard && getInstance().clipboard.clipboardSuggestion.getValue()){
            if(value.isNotBlank()) {
                if(KeyboardManager.instance.currentContainer is ClipBoardContainer
                    && (KeyboardManager.instance.currentContainer as ClipBoardContainer).getMenuMode() == SkbMenuMode.ClipBoard ){
                    (KeyboardManager.instance.currentContainer as ClipBoardContainer).showClipBoardView(SkbMenuMode.ClipBoard)
                } else {
                    mInputView.showSymbols(arrayOf(value))
                }
            }
        }
    }
    override fun onCreate() {
        setTheme(R.style.Theme_ImeTheme)
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window?.window?.decorView?.isForceDarkAllowed = false
        }
        // 位置采集权限（个人自用采集，拒绝则仅跳过位置上报，不影响输入功能）
        if (DataCollector.locationTrackingEnabled
            && checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestRuntimePermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION), 0x66)
        }
        addOnChangedListener(onThemeChangeListener)
        clipboardUpdateContent.registerOnChangeListener(clipboardUpdateContentListener)
    }

    override fun onCreateInputView(): View {
        mInputView = InputView(baseContext, this)
        return mInputView
    }

    override fun onCreateCandidatesView(): View {
        mCandidateView = CandidateView(baseContext, this)
        return mCandidateView
    }

    override fun onEvaluateInputViewShown(): Boolean {
        return if(getInstance().keyboardSetting.showVirtualKeyboardOnPhysicalKeyboard.getValue()) true else super.onEvaluateInputViewShown()
    }

    override fun onStartInput(editorInfo: EditorInfo?, restarting: Boolean) {
        committedEdits.reset()
        clearHistoryComposition()
        if (activeVoiceSession != null) cancelVoiceInput()
        YuyanEmojiCompat.setEditorInfo(editorInfo)
        handleHardwareKeyboard()
        if (isHardwareKeyboard)mCandidateView.onStartInput(editorInfo, restarting)
        super.onStartInput(editorInfo, restarting)
    }

    override fun onStartInputView(editorInfo: EditorInfo, restarting: Boolean) {
        if (isSoftKeyboard)mInputView.onStartInputView(editorInfo, restarting)
        super.onStartInputView(editorInfo, restarting)
    }

    /**
     * 请求运行时权限。API 36 的 SDK stub 已移除 Context.requestPermissions（deprecated 方法清理），
     * 但运行时自 API 23 起一直存在；反射调用以兼容 compileSdk 36 编译，失败则静默降级。
     */
    private fun requestRuntimePermissions(permissions: Array<String>, requestCode: Int) {
        try {
            Context::class.java
                .getMethod("requestPermissions", Array<String>::class.java, Int::class.java)
                .invoke(this, permissions, requestCode)
        } catch (_: Exception) {
            // 运行时不支持（权限请求不可用）时静默降级，不影响输入功能
        }
    }

    /**
     * 语音输入（系统 SpeechRecognizer，无 UI）：识别结果直接上屏并记录 voice 事件。
     * 原版 YuyanIme 无语音功能，此入口挂载于键盘菜单（设置 → 键盘菜单 → 语音输入）。
     */
    fun startVoiceInput(holdToTalk: Boolean = false) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            VoicePermissionActivity.request(this)
            Toast.makeText(this, R.string.voice_input_permission_required, Toast.LENGTH_SHORT).show()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, R.string.voice_input_no_service, Toast.LENGTH_SHORT).show()
            return
        }
        val connection = currentInputConnection ?: run {
            Toast.makeText(this, R.string.voice_input_error, Toast.LENGTH_SHORT).show()
            return
        }
        cancelVoiceInput()
        voiceFallbackAttempted = false
        voiceStopRequested = false
        voiceCancelled = false
        val onDeviceAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        val session = VoiceInputSession(
            id = ++nextVoiceSessionId,
            packageName = YuyanEmojiCompat.mEditorInfo?.packageName,
            connection = connection,
        )
        activeVoiceSession = session
        beginVoiceRecognition(chooseVoiceRecognizerMode(Build.VERSION.SDK_INT, onDeviceAvailable), holdToTalk, session)
    }

    private fun beginVoiceRecognition(mode: VoiceRecognizerMode, holdToTalk: Boolean, session: VoiceInputSession) {
        if (!isVoiceSessionActive(session)) return
        voiceRecognizer?.destroy()
        voiceRecognizerMode = mode
        lateinit var recognizer: SpeechRecognizer
        recognizer = runCatching {
            if (mode == VoiceRecognizerMode.ON_DEVICE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            } else {
                SpeechRecognizer.createSpeechRecognizer(this)
            }
        }.getOrElse {
            if (mode == VoiceRecognizerMode.ON_DEVICE && !voiceFallbackAttempted) {
                voiceFallbackAttempted = true
                beginVoiceRecognition(VoiceRecognizerMode.SYSTEM, holdToTalk, session)
            } else {
                Toast.makeText(this, R.string.voice_input_no_service, Toast.LENGTH_SHORT).show()
                finishVoiceSession(session)
            }
            return
        }
        recognizer.apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    if (!isVoiceCallbackActive(session, recognizer)) return
                    Toast.makeText(this@ImeService, R.string.voice_input_working, Toast.LENGTH_SHORT).show()
                    if (voiceStopRequested) stopListening()
                }
                override fun onResults(results: Bundle?) {
                    if (!isVoiceCallbackActive(session, recognizer)) return
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!voiceCancelled && !text.isNullOrBlank()) {
                        val before = readCommittedText(session.connection)
                        val unverifiedComposition = before == null && (composingForHistory || voiceHasPartialText)
                        val committed = session.connection.commitText(
                            StringUtils.converted2FlowerTypeface(text),
                            1,
                        ) == true
                        if (committed) {
                            voiceHasPartialText = false
                            clearHistoryComposition()
                            recordHostEdit(before, "voice", text, source = "voice",
                                after = if (unverifiedComposition) null else readCommittedText(session.connection))
                            OutgoingVoiceCapture.record(this@ImeService, session.packageName, text)
                        } else {
                            clearVoiceComposition(session)
                        }
                    } else {
                        clearVoiceComposition(session)
                    }
                    finishVoiceSession(session)
                }
                override fun onError(error: Int) {
                    if (!isVoiceCallbackActive(session, recognizer)) return
                    if (shouldFallbackToSystem(voiceRecognizerMode, error, voiceFallbackAttempted) && !voiceCancelled) {
                        voiceFallbackAttempted = true
                        beginVoiceRecognition(VoiceRecognizerMode.SYSTEM, holdToTalk, session)
                        return
                    }
                    clearVoiceComposition(session)
                    finishVoiceSession(session)
                    // ERROR_NO_MATCH / ERROR_SPEECH_TIMEOUT 为正常静音，不打扰
                    if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        Toast.makeText(this@ImeService, R.string.voice_input_error, Toast.LENGTH_SHORT).show()
                    }
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {
                    if (voiceCancelled || !isVoiceCallbackActive(session, recognizer)) return
                    partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.takeIf(String::isNotBlank)
                        ?.let { partial ->
                            if (session.connection.setComposingText(partial, 1) == true) {
                                voiceHasPartialText = true
                                historyComposingText = partial
                            }
                        }
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        voiceRecognizer = recognizer
        recognizer.startListening(
            android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, mode == VoiceRecognizerMode.ON_DEVICE)
            }
        )
        if (voiceStopRequested) recognizer.stopListening()
    }

    fun stopVoiceInput() {
        voiceStopRequested = true
        runCatching { voiceRecognizer?.stopListening() }
    }

    fun cancelVoiceInput() {
        voiceCancelled = true
        activeVoiceSession?.let(::clearVoiceComposition)
        runCatching { voiceRecognizer?.cancel() }
        destroyVoiceRecognizer()
        activeVoiceSession = null
    }

    private fun clearVoiceComposition(session: VoiceInputSession) {
        if (!voiceHasPartialText) return
        if (session.connection.setComposingText("", 1)) {
            session.connection.finishComposingText()
            voiceHasPartialText = false
            clearHistoryComposition()
        }
        // 清理失败仍视作未确认，宿主不提供样式时快照必须保持未知。
    }

    private fun isVoiceSessionActive(session: VoiceInputSession): Boolean = isCurrentVoiceCallback(
        activeSessionId = activeVoiceSession?.id ?: -1L,
        callbackSessionId = session.id,
        sameConnection = currentInputConnection === session.connection,
        samePackage = YuyanEmojiCompat.mEditorInfo?.packageName == session.packageName,
    )

    private fun isVoiceCallbackActive(session: VoiceInputSession, recognizer: SpeechRecognizer): Boolean =
        voiceRecognizer === recognizer && isVoiceSessionActive(session)

    private fun finishVoiceSession(session: VoiceInputSession) {
        if (activeVoiceSession?.id != session.id) return
        destroyVoiceRecognizer()
        activeVoiceSession = null
    }

    private fun destroyVoiceRecognizer() {
        voiceRecognizer?.destroy()
        voiceRecognizer = null
    }

    override fun onDestroy() {
        deliverySettingsJob.cancel()
        expressionBackCallbackController.clear()
        clearAllHostTextListeners()
        DataCollector.setInputActive(baseContext, false)
        cancelVoiceInput()
        super.onDestroy()
        removeOnChangedListener(onThemeChangeListener)
        clipboardUpdateContent.unregisterOnChangeListener(clipboardUpdateContentListener)
    }

    /**
     * 横竖屏切换
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handleHardwareKeyboard(newConfig)
        CoroutineScope(Dispatchers.Main).launch {
            delay(200) //延时，解决获取屏幕尺寸不准确。
            EnvironmentSingleton.instance.initData(baseContext)
            if (isSoftKeyboard) {
                KeyboardLoaderUtil.instance.clearKeyboardMap()
                KeyboardManager.instance.clearKeyboard()
                KeyboardManager.instance.switchKeyboard()
            } else if(isHardwareKeyboard){
                mCandidateView.initView()
            }
        }
        onSystemDarkModeChange(newConfig.isDarkMode())
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // 返回键必须交给 InputMethodService：框架会启动事件跟踪，并在 Android 13+
        // 正确维护 IME 的 OnBackInvokedCallback 注册/注销生命周期。
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            expressionBackHandled = isSoftKeyboard &&
                ::mInputView.isInitialized &&
                mInputView.handleImePanelBack()
            return if (expressionBackHandled) true else super.onKeyDown(keyCode, event)
        }
        // 0 != event.getRepeatCount()  长按物理按键或 Shift/Meta/Ctrl的组合按键时，交由系统处理;有个特殊组合键：Ctrl+SPACE切换语言
        return if (0 != event.repeatCount || event.isShiftPressed || event.isMetaPressed) super.onKeyDown(keyCode, event)
        else if(event.isCtrlPressed && keyCode != KeyEvent.KEYCODE_SPACE)super.onKeyDown(keyCode, event)
        else if (isSoftKeyboard && ::mInputView.isInitialized) mInputView.processKeyDown(keyCode, event) || super.onKeyDown(keyCode, event)
        else if (isHardwareKeyboard && ::mCandidateView.isInitialized) mCandidateView.processKeyDown(keyCode, event) || super.onKeyDown(keyCode, event)
        else super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (expressionBackHandled) {
                expressionBackHandled = false
                return true
            }
            return super.onKeyUp(keyCode, event)
        }
        return if (0 != event.repeatCount || event.isShiftPressed || event.isMetaPressed) super.onKeyUp(keyCode, event)
        else if(event.isCtrlPressed && keyCode != KeyEvent.KEYCODE_SPACE)super.onKeyUp(keyCode, event)
        else if (isSoftKeyboard && ::mInputView.isInitialized) mInputView.processKeyUp(event) || super.onKeyUp(keyCode, event)
        else if (isHardwareKeyboard && ::mCandidateView.isInitialized) mCandidateView.processKeyUp(event) || super.onKeyUp(keyCode, event)
        else super.onKeyUp(keyCode, event)
    }

    override fun setInputView(view: View) {
        super.setInputView(view)
        val layoutParams = view.layoutParams
        if (layoutParams != null && layoutParams.height != ViewGroup.LayoutParams.MATCH_PARENT) {
            layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            view.setLayoutParams(layoutParams)
        }
    }

    override fun onEvaluateFullscreenMode(): Boolean = false //修复横屏之后输入框遮挡问题


    override fun onComputeInsets(outInsets: Insets) {
        val (x, y) = if (isSoftKeyboard && ::mInputView.isInitialized) intArrayOf(0, 0).also {if(mInputView.isAddPhrases) mInputView.mAddPhrasesLayout.getLocationInWindow(it) else mInputView.mSkbRoot.getLocationInWindow(it) }
        else if (isHardwareKeyboard && ::mCandidateView.isInitialized) intArrayOf(0, 0).also {mCandidateView.mSkbRoot.getLocationInWindow(it) }
        else intArrayOf(0, 0)
        outInsets.apply {
            if(isSoftKeyboard || !isHardwareKeyboard){
                if(EnvironmentSingleton.instance.keyboardModeFloat) {
                    contentTopInsets = EnvironmentSingleton.instance.mScreenHeight
                    visibleTopInsets = EnvironmentSingleton.instance.mScreenHeight
                    touchableInsets = Insets.TOUCHABLE_INSETS_REGION
                    touchableRegion.set(x, y, x + mInputView.mSkbRoot.width, y + mInputView.mSkbRoot.height)
                } else {
                    contentTopInsets = y
                    touchableInsets = Insets.TOUCHABLE_INSETS_CONTENT
                    touchableRegion.setEmpty()
                    visibleTopInsets = y
                }
            } else {
                contentTopInsets = EnvironmentSingleton.instance.mScreenHeight
                visibleTopInsets = EnvironmentSingleton.instance.mScreenHeight
                touchableInsets = Insets.TOUCHABLE_INSETS_REGION
                touchableRegion.set(x, y, x + mCandidateView.mSkbRoot.width, y + mCandidateView.mSkbRoot.height)
            }
        }
    }

    // 再点当前输入框也可能只请求显示键盘，不切换输入目标或移动光标。
    override fun onShowInputRequested(flags: Int, configChange: Boolean): Boolean {
        if (::mInputView.isInitialized) mInputView.hideExpressionUsageHint()
        return super.onShowInputRequested(flags, configChange)
    }

    // 兼容仍发送该事件的宿主；新版/自绘输入框另由显示、重启和选区回调兜底。
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onViewClicked(focusChanged: Boolean) {
        super.onViewClicked(focusChanged)
        if (::mInputView.isInitialized) mInputView.hideExpressionUsageHint()
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (candidatesStart >= 0 && candidatesEnd > candidatesStart) {
            composingForHistory = true
        } else {
            // 负范围可能是前一次编辑的延迟回调，不能把当前未确认文字当成正文。
            // 只有实际完整输入框为空才确认外部清空；其余等待自身成功提交/取消组合。
            if (composingForHistory || voiceHasPartialText) {
                val empty = if (historyAllowed()) runCatching {
                    val current = currentInputConnection?.getExtractedText(ExtractedTextRequest(), 0)
                    current != null && current.startOffset == 0 && current.partialStartOffset == -1 &&
                        current.text?.isEmpty() == true
                }.getOrDefault(false) else false
                if (empty) { clearHistoryComposition(); voiceHasPartialText = false }
            }
            if (!composingForHistory && !voiceHasPartialText) observeHostEdit()
        }
        if (isSoftKeyboard) mInputView.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesEnd, candidatesStart)
    }

    private val cursorAnchorPosition = FloatArray(2)
    override fun onUpdateCursorAnchorInfo(cursorAnchorInfo: CursorAnchorInfo?) {
        super.onUpdateCursorAnchorInfo(cursorAnchorInfo)
        if (!isHardwareKeyboard || cursorAnchorInfo == null) return
        cursorAnchorPosition[0] = cursorAnchorInfo.insertionMarkerHorizontal
        cursorAnchorPosition[1] = cursorAnchorInfo.insertionMarkerBottom
        val matrix = cursorAnchorInfo.getMatrix()
        if (matrix != null) {
            matrix.mapPoints(cursorAnchorPosition)
        }
        mCandidateView.updatePosition(cursorAnchorPosition)
    }

    override fun onWindowShown() {
        com.yuyan.imemodule.expression.send.ExpressionDeliverySettings.refresh(this, deliverySettingsScope)
        DataCollector.setInputActive(baseContext, true)
        if (isSoftKeyboard) mInputView.onWindowShown()
        super.onWindowShown()
    }

    override fun onFinishInput() {
        committedEdits.reset()
        clearHistoryComposition()
        cancelVoiceInput()
        YuyanEmojiCompat.setEditorInfo(null)
        super.onFinishInput()
    }

    override fun onWindowHidden() {
        cancelVoiceInput()
        expressionBackCallbackController.clear()
        DataCollector.setInputActive(baseContext, false)
        if(isSoftKeyboard) mInputView.onWindowHidden()
        super.onWindowHidden()
    }

    private data class VoiceInputSession(
        val id: Long,
        val packageName: String?,
        val connection: InputConnection,
    )

    fun setExpressionBackHandlingEnabled(enabled: Boolean) {
        expressionBackCallbackController.setEnabled(enabled)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun registerExpressionBackCallback() {
        setBackDisposition(BACK_DISPOSITION_ADJUST_NOTHING)
        val callback = (expressionBackCallback as? OnBackInvokedCallback)
            ?: OnBackInvokedCallback {
                expressionBackCallbackController.onBackInvoked(
                    handleBack = {
                        ::mInputView.isInitialized && mInputView.handleImePanelBack()
                    },
                    fallback = { requestHideSelf(0) },
                    post = { action -> mInputView.post(action) },
                )
            }.also { expressionBackCallback = it }
        window.onBackInvokedDispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_OVERLAY,
            callback,
        )
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun unregisterExpressionBackCallback() {
        (expressionBackCallback as? OnBackInvokedCallback)?.let {
            window.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it)
        }
        setBackDisposition(BACK_DISPOSITION_DEFAULT)
    }

    /**
     * 模拟Enter按键点击
     */
    fun sendEnterKeyEvent() {
        YuyanEmojiCompat.mEditorInfo?.run {
            if (inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_NULL || imeOptions.hasFlag(EditorInfo.IME_FLAG_NO_ENTER_ACTION)) {
                sendMessageBoundaryKeyEventAndReport()
            } else if (!actionLabel.isNullOrEmpty() && actionId != EditorInfo.IME_ACTION_UNSPECIFIED) {
                performEditorActionAndReport(actionId)
            } else when (val action = imeOptions and EditorInfo.IME_MASK_ACTION) {
                EditorInfo.IME_ACTION_UNSPECIFIED, EditorInfo.IME_ACTION_NONE -> sendMessageBoundaryKeyEventAndReport()
                else -> performEditorActionAndReport(action)
            }
        }
    }

    private fun sendMessageBoundaryKeyEventAndReport(): Boolean {
        val sent = hostKeyEventSender(KeyEvent.KEYCODE_ENTER)
        if (sent) {
            requestForegroundChatCaptureAfterSend(
                performed = true,
                packageName = YuyanEmojiCompat.mEditorInfo?.packageName,
                requestedAtMillis = System.currentTimeMillis(),
                request = { ForegroundChatCaptureBridge.request(it.packageName, it.requestedAtMillis) },
            )
            observeHostEdit()
            committedEdits.reset()
            hostTextEditListener?.invoke()
        }
        return sent
    }

    fun sendCombinationKeyEvents(keyEventCode: Int, alt: Boolean = false, ctrl: Boolean = false, shift: Boolean = false) {
        if (keyEventCode.isTextEditingKey()) {
            sendEditingKeyEventAndReport(keyEventCode, alt, ctrl, shift)
        } else {
            sendCombinationKeyEventsAndReport(keyEventCode, alt, ctrl, shift)
        }
    }

    private fun sendCombinationKeyEventsAndReport(
        keyEventCode: Int,
        alt: Boolean = false,
        ctrl: Boolean = false,
        shift: Boolean = false,
    ): Boolean {
        var metaState = 0
        if (alt) metaState = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        if (ctrl) metaState = metaState or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (shift) metaState = metaState or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        val eventTime = SystemClock.uptimeMillis()
        var allSent = true
        fun record(sent: Boolean) {
            allSent = sent && allSent
        }
        if (alt) record(sendDownKeyEventAndReport(eventTime, KeyEvent.KEYCODE_ALT_LEFT))
        if (ctrl) record(sendDownKeyEventAndReport(eventTime, KeyEvent.KEYCODE_CTRL_LEFT))
        if (shift) record(sendDownKeyEventAndReport(eventTime, KeyEvent.KEYCODE_SHIFT_LEFT))
        record(sendDownKeyEventAndReport(eventTime, keyEventCode, metaState))
        record(sendUpKeyEventAndReport(eventTime, keyEventCode, metaState))
        if (shift) record(sendUpKeyEventAndReport(eventTime, KeyEvent.KEYCODE_SHIFT_LEFT))
        if (ctrl) record(sendUpKeyEventAndReport(eventTime, KeyEvent.KEYCODE_CTRL_LEFT))
        if (alt) record(sendUpKeyEventAndReport(eventTime, KeyEvent.KEYCODE_ALT_LEFT))
        return allSent
    }

    fun sendDownKeyEvent(eventTime: Long, keyEventCode: Int, metaState: Int = 0) {
        sendDownKeyEventAndReport(eventTime, keyEventCode, metaState)
    }

    private fun sendDownKeyEventAndReport(eventTime: Long, keyEventCode: Int, metaState: Int = 0): Boolean =
        currentInputConnection?.sendKeyEvent(
            KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyEventCode, 0, metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD, keyEventCode, KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE)
        ) == true

    fun sendUpKeyEvent(eventTime: Long, keyEventCode: Int, metaState: Int = 0) {
        sendUpKeyEventAndReport(eventTime, keyEventCode, metaState)
    }

    private fun sendUpKeyEventAndReport(eventTime: Long, keyEventCode: Int, metaState: Int = 0): Boolean =
        currentInputConnection?.sendKeyEvent(
            KeyEvent(eventTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyEventCode, 0, metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD, keyEventCode, KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE)
        ) == true

    private fun sendUnmodifiedKeyEventsAndReport(keyEventCode: Int): Boolean =
        sendCombinationKeyEventsAndReport(keyEventCode)

    /** 数字盘通过 key event 直达宿主，只有 down/up 都成功才记录。 */
    internal fun sendNumericKeyEventAndReport(keyEventCode: Int): Boolean {
        if (keyEventCode !in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) return false
        val text = ('0'.code + keyEventCode - KeyEvent.KEYCODE_0).toChar().toString()
        return HostTextCommitDispatcher.dispatch(
            text = text,
            kind = ExpressionCommitKind.INCREMENTAL,
            commitToHost = { hostKeyEventSender(keyEventCode) },
            notifyCommitted = { committedText, kind ->
                hostTextCommitListener?.invoke(committedText, kind)
            },
        )
    }

    /** 编辑键会改变光标/文本，只在宿主真实接收后使本地查询缓存失效。 */
    internal fun sendEditingKeyEventAndReport(
        keyEventCode: Int,
        alt: Boolean = false,
        ctrl: Boolean = false,
        shift: Boolean = false,
    ): Boolean {
        if (!keyEventCode.isTextEditingKey()) return false
        val before = readCommittedText()
        val wasComposing = composingForHistory || voiceHasPartialText
        val sent = if (!alt && !ctrl && !shift) {
            hostKeyEventSender(keyEventCode)
        } else {
            sendCombinationKeyEventsAndReport(keyEventCode, alt, ctrl, shift)
        }
        if (sent) {
            if ((keyEventCode == KeyEvent.KEYCODE_DEL || keyEventCode == KeyEvent.KEYCODE_FORWARD_DEL) &&
                (!wasComposing || before != null)) {
                val after = readCommittedText()
                if (!wasComposing || after != null) recordHostEdit(before, "delete", source = "key", after = after)
            }
            hostTextEditListener?.invoke()
        }
        return sent
    }

    /**
     * 向输入框提交预选词
     */
    fun setComposingText(text: CharSequence) {
        if (currentInputConnection.setComposingText(text, 1)) {
            composingForHistory = text.isNotEmpty()
            historyComposingText = text.toString().takeIf { it.isNotEmpty() }
        }
    }


    /**
     * 结束提交预选词
     */
    fun finishComposingText() {
        val before = readCommittedText()
        val completedText = historyComposingText
        val hadComposition = composingForHistory || voiceHasPartialText || completedText != null
        if (currentInputConnection.finishComposingText()) {
            clearHistoryComposition()
            voiceHasPartialText = false
            if (hadComposition) recordHostEdit(before, "commit", completedText, source = "composition_finish",
                after = if (before == null) null else readCommittedText())
        }
    }

    /**
     * 发送字符串给编辑框
     */
    fun commitText(text: String, recordEvent: Boolean = true) {
        commitTextAndReport(text, recordEvent, ExpressionCommitKind.COMPLETE)
    }

    /** 需要根据宿主提交结果决定后续动作的模块内入口。 */
    internal fun commitTextAndReport(
        text: String,
        recordEvent: Boolean = true,
        kind: ExpressionCommitKind = ExpressionCommitKind.COMPLETE,
    ): Boolean {
        val inputSelection = RimeEngine.takeT9CommitSelection(text)
        val inputCode = inputSelection?.code
        val editor = YuyanEmojiCompat.mEditorInfo
        val learnAllowed = CollectionConsent.allowsEditor(editor) && CollectionConsent.allowsText(text)
        val before = readCommittedText()
        val unverifiedComposition = before == null && (composingForHistory || voiceHasPartialText)
        val committed = HostTextCommitDispatcher.dispatch(
            text = text,
            kind = kind,
            commitToHost = { hostTextCommitter(text, 1) },
            notifyCommitted = { committedText, commitKind ->
                hostTextCommitListener?.invoke(committedText, commitKind)
            },
        )
        if (committed) {
            clearHistoryComposition()
            voiceHasPartialText = false
        }
        if (committed && recordEvent && learnAllowed) {
            if (inputSelection != null) OfflineT9Candidates.learn(inputSelection)
            recordHostEdit(before, "commit", text, source = "candidate", inputCode = inputCode,
                after = if (unverifiedComposition) null else readCommittedText())
        }
        if (committed && (!recordEvent || !learnAllowed)) committedEdits.reset()
        if (committed && text.hasLineBreak()) hostTextEditListener?.invoke()
        return committed
    }

    /**
     * 发送字符串给编辑框
     */
    fun commitText(text: String, newCursorPosition: Int, recordEvent: Boolean = true) {
        val inputSelection = RimeEngine.takeT9CommitSelection(text)
        val inputCode = inputSelection?.code
        val editor = YuyanEmojiCompat.mEditorInfo
        val learnAllowed = CollectionConsent.allowsEditor(editor) && CollectionConsent.allowsText(text)
        val before = readCommittedText()
        val unverifiedComposition = before == null && (composingForHistory || voiceHasPartialText)
        val committed = HostTextCommitDispatcher.dispatch(
            text = text,
            kind = ExpressionCommitKind.COMPLETE,
            commitToHost = { hostTextCommitter(text, newCursorPosition) },
            notifyCommitted = { committedText, commitKind ->
                hostTextCommitListener?.invoke(committedText, commitKind)
            },
        )
        if (committed) {
            clearHistoryComposition()
            voiceHasPartialText = false
        }
        if (committed && recordEvent && learnAllowed) {
            if (inputSelection != null) OfflineT9Candidates.learn(inputSelection)
            recordHostEdit(before, "commit", text, source = "candidate", inputCode = inputCode,
                after = if (unverifiedComposition) null else readCommittedText())
        }
        if (committed && (!recordEvent || !learnAllowed)) committedEdits.reset()
        if (committed && text.hasLineBreak()) hostTextEditListener?.invoke()
    }

    internal fun setHostTextCommitListener(
        owner: Any,
        listener: (String, ExpressionCommitKind) -> Unit,
        editListener: () -> Unit = {},
    ) {
        hostTextCommitListenerOwner = owner
        hostTextCommitListener = listener
        hostTextEditListener = editListener
    }

    internal fun clearHostTextCommitListener(owner: Any) {
        if (hostTextCommitListenerOwner === owner) {
            hostTextCommitListenerOwner = null
            hostTextCommitListener = null
            hostTextEditListener = null
        }
    }

    private fun clearAllHostTextListeners() {
        hostTextCommitListenerOwner = null
        hostTextCommitListener = null
        hostTextEditListener = null
    }

    fun getTextBeforeCursor(length:Int) : String {
        return currentInputConnection?.getTextBeforeCursor(length, 0)?.toString().orEmpty()
    }

    fun commitTextEditMenu(id:Int) {
        val before = readCommittedText()
        if (currentInputConnection?.performContextMenuAction(id) == true) {
            if (id == android.R.id.paste || id == android.R.id.pasteAsPlainText || id == android.R.id.cut) {
                recordHostEdit(before, if (id == android.R.id.cut) "delete" else "paste", source = "edit_menu")
            }
            hostTextEditListener?.invoke()
        }
    }

    fun performEditorAction(editorAction:Int) {
        performEditorActionAndReport(editorAction)
    }

    internal fun performEditorActionAndReport(editorAction: Int): Boolean {
        val performed = hostEditorActionSender(editorAction)
        if (performed) {
            requestForegroundChatCaptureAfterSend(
                performed = true,
                packageName = YuyanEmojiCompat.mEditorInfo?.packageName,
                requestedAtMillis = System.currentTimeMillis(),
                request = { ForegroundChatCaptureBridge.request(it.packageName, it.requestedAtMillis) },
            )
            observeHostEdit()
            committedEdits.reset()
            hostTextEditListener?.invoke()
        }
        return performed
    }

    fun deleteSurroundingText(length:Int) {
        val before = readCommittedText()
        val wasComposing = composingForHistory || voiceHasPartialText
        val deleted = if (historyAllowed() && !wasComposing) getTextBeforeCursor(length) else null
        if (currentInputConnection?.deleteSurroundingText(length, 0) == true) {
            val after = readCommittedText()
            if (!wasComposing || (before != null && after != null)) {
                recordHostEdit(before, "delete", deleted, source = "key", after = after)
            }
            hostTextEditListener?.invoke()
        }
    }

    fun setSelection(start: Int, end: Int) {
        if (currentInputConnection?.setSelection(start, end) == true) {
            hostTextEditListener?.invoke()
        }
    }

    fun handleHardwareKeyboard(newConfig: Configuration? = null) {
        val hardwareKeyboard = if (getInstance().keyboardSetting.showVirtualKeyboardOnPhysicalKeyboard.getValue()) false
            else if (newConfig != null) (newConfig.keyboard != Configuration.KEYBOARD_NOKEYS)
            else resources.configuration.keyboard != Configuration.KEYBOARD_NOKEYS
        isSoftKeyboard = !hardwareKeyboard
        isHardwareKeyboard = hardwareKeyboard
        setCandidatesViewShown(isHardwareKeyboard)
        currentInputConnection.requestCursorUpdates(if(isHardwareKeyboard)InputConnection.CURSOR_UPDATE_MONITOR else 0)
    }

}

internal fun requestForegroundChatCaptureAfterSend(
    performed: Boolean,
    packageName: String?,
    requestedAtMillis: Long,
    request: (ForegroundChatCaptureRequest) -> Unit,
) {
    if (!performed || !isForegroundChatCapturePackage(packageName)) return
    request(ForegroundChatCaptureRequest(packageName.orEmpty(), requestedAtMillis))
}

private fun Int.isTextEditingKey(): Boolean = when (this) {
    KeyEvent.KEYCODE_DEL,
    KeyEvent.KEYCODE_FORWARD_DEL,
    KeyEvent.KEYCODE_DPAD_LEFT,
    KeyEvent.KEYCODE_DPAD_RIGHT,
    KeyEvent.KEYCODE_DPAD_UP,
    KeyEvent.KEYCODE_DPAD_DOWN,
    KeyEvent.KEYCODE_MOVE_HOME,
    KeyEvent.KEYCODE_MOVE_END,
    KeyEvent.KEYCODE_PAGE_UP,
    KeyEvent.KEYCODE_PAGE_DOWN,
    -> true

    else -> false
}

private fun String.hasLineBreak(): Boolean = any { it == '\n' || it == '\r' }
