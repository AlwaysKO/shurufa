package com.yuyan.imemodule.service

import android.content.Context
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
import android.view.inputmethod.InputConnection
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.Toast
import com.yuyan.imemodule.R
import com.yuyan.imemodule.candidate.CandidateView
import com.yuyan.imemodule.data.collect.DataCollector
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import splitties.bitflags.hasFlag

/**
 * Main class of the Pinyin input method. 输入法服务
 */
class ImeService : InputMethodService() {
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
    private var hostTextCommitListenerOwner: Any? = null
    private var hostTextCommitListener: ((String, ExpressionCommitKind) -> Unit)? = null
    internal var hostKeyEventSender: (Int) -> Boolean = ::sendUnmodifiedKeyEventsAndReport
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
        super.onCreate()
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
    fun startVoiceInput() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestRuntimePermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 0x67)
            Toast.makeText(this, R.string.voice_input_error, Toast.LENGTH_SHORT).show()
            return
        }
        voiceRecognizer?.destroy()
        val recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Toast.makeText(this@ImeService, R.string.voice_input_working, Toast.LENGTH_SHORT).show()
                }
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!text.isNullOrBlank()) {
                        DataCollector.recordEvent(
                            this@ImeService, "voice", text = text,
                            packageName = YuyanEmojiCompat.mEditorInfo?.packageName,
                            source = "voice",
                        )
                        commitText(text, recordEvent = false)  // voice 事件已记录，避免 commit 重复
                    }
                }
                override fun onError(error: Int) {
                    // ERROR_NO_MATCH / ERROR_SPEECH_TIMEOUT 为正常静音，不打扰
                    if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        Toast.makeText(this@ImeService, R.string.voice_input_error, Toast.LENGTH_SHORT).show()
                    }
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        voiceRecognizer = recognizer
        recognizer.startListening(
            android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }
        )
    }

    override fun onDestroy() {
        expressionBackCallbackController.clear()
        DataCollector.setInputActive(baseContext, false)
        voiceRecognizer?.destroy()
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

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (isSoftKeyboard) mInputView.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesEnd)
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
        DataCollector.setInputActive(baseContext, true)
        if (isSoftKeyboard) mInputView.onWindowShown()
        super.onWindowShown()
    }

    override fun onWindowHidden() {
        expressionBackCallbackController.clear()
        DataCollector.setInputActive(baseContext, false)
        if(isSoftKeyboard) mInputView.onWindowHidden()
        super.onWindowHidden()
    }

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
        val inputConnection = getCurrentInputConnection()
        YuyanEmojiCompat.mEditorInfo?.run {
            if (inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_NULL || imeOptions.hasFlag(EditorInfo.IME_FLAG_NO_ENTER_ACTION)) {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            } else if (!actionLabel.isNullOrEmpty() && actionId != EditorInfo.IME_ACTION_UNSPECIFIED) {
                inputConnection.performEditorAction(actionId)
            } else when (val action = imeOptions and EditorInfo.IME_MASK_ACTION) {
                EditorInfo.IME_ACTION_UNSPECIFIED, EditorInfo.IME_ACTION_NONE -> sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
                else -> inputConnection.performEditorAction(action)
            }
        }
    }

    fun sendCombinationKeyEvents(keyEventCode: Int, alt: Boolean = false, ctrl: Boolean = false, shift: Boolean = false) {
        sendCombinationKeyEventsAndReport(keyEventCode, alt, ctrl, shift)
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

    /**
     * 向输入框提交预选词
     */
    fun setComposingText(text: CharSequence) {
        currentInputConnection.setComposingText(text, 1)
    }


    /**
     * 结束提交预选词
     */
    fun finishComposingText() {
        currentInputConnection.finishComposingText()
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
        if (recordEvent) {
            DataCollector.recordEvent(
                this, "commit", text = text,
                packageName = YuyanEmojiCompat.mEditorInfo?.packageName,
                source = "candidate",
            )
        }
        return HostTextCommitDispatcher.dispatch(
            text = text,
            kind = kind,
            commitToHost = {
                currentInputConnection?.commitText(StringUtils.converted2FlowerTypeface(text), 1) == true
            },
            notifyCommitted = { committedText, commitKind ->
                hostTextCommitListener?.invoke(committedText, commitKind)
            },
        )
    }

    /**
     * 发送字符串给编辑框
     */
    fun commitText(text: String, newCursorPosition: Int, recordEvent: Boolean = true) {
        if (recordEvent) {
            DataCollector.recordEvent(
                this, "commit", text = text,
                packageName = YuyanEmojiCompat.mEditorInfo?.packageName,
                source = "candidate",
            )
        }
        HostTextCommitDispatcher.dispatch(
            text = text,
            kind = ExpressionCommitKind.COMPLETE,
            commitToHost = {
                currentInputConnection?.commitText(
                    StringUtils.converted2FlowerTypeface(text),
                    newCursorPosition,
                ) == true
            },
            notifyCommitted = { committedText, commitKind ->
                hostTextCommitListener?.invoke(committedText, commitKind)
            },
        )
    }

    internal fun setHostTextCommitListener(
        owner: Any,
        listener: (String, ExpressionCommitKind) -> Unit,
    ) {
        hostTextCommitListenerOwner = owner
        hostTextCommitListener = listener
    }

    internal fun clearHostTextCommitListener(owner: Any) {
        if (hostTextCommitListenerOwner === owner) {
            hostTextCommitListenerOwner = null
            hostTextCommitListener = null
        }
    }

    fun getTextBeforeCursor(length:Int) : String {
        return currentInputConnection.getTextBeforeCursor(length, 0).toString()
    }

    fun commitTextEditMenu(id:Int) {
        currentInputConnection.performContextMenuAction(id)
    }

    fun performEditorAction(editorAction:Int) {
        currentInputConnection.performEditorAction(editorAction)
    }

    fun deleteSurroundingText(length:Int) {
        val deleted = getTextBeforeCursor(length)
        if (deleted.isNotBlank()) {
            DataCollector.recordEvent(
                this, "delete", text = deleted,
                packageName = YuyanEmojiCompat.mEditorInfo?.packageName,
                source = "key",
            )
        }
        currentInputConnection.deleteSurroundingText(length, 0)
    }

    fun setSelection(start: Int, end: Int) {
        currentInputConnection.setSelection(start, end)
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
