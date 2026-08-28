package com.yuyan.imemodule.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.util.DisplayMetrics
import android.view.DisplayCutout
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.scale
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.get
import androidx.core.view.postDelayed
import com.yuyan.imemodule.R
import com.yuyan.imemodule.application.CustomConstant
import com.yuyan.imemodule.callback.CandidateViewListener
import com.yuyan.imemodule.callback.IResponseKeyEvent
import com.yuyan.imemodule.data.completion.CompletionSync
import com.yuyan.imemodule.data.collect.DataCollector
import com.yuyan.imemodule.data.collect.ServerConfig
import com.yuyan.imemodule.data.emojicon.EmojiconData.SymbolPreset
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.database.DataBaseKT
import com.yuyan.imemodule.database.entry.Phrase
import com.yuyan.imemodule.entity.StringQueue
import com.yuyan.imemodule.entity.keyboard.SoftKey
import com.yuyan.imemodule.expression.ExpressionCache
import com.yuyan.imemodule.expression.ExpressionCatalog
import com.yuyan.imemodule.expression.ChatEditorGate
import com.yuyan.imemodule.expression.ExpressionComposingTextSource
import com.yuyan.imemodule.expression.ExpressionCommitKind
import com.yuyan.imemodule.expression.ExpressionInputTargetTracker
import com.yuyan.imemodule.expression.ExpressionManualSearch
import com.yuyan.imemodule.expression.ExpressionPanelState
import com.yuyan.imemodule.expression.ExpressionPanelPresentation
import com.yuyan.imemodule.expression.ExpressionQueryCoordinator
import com.yuyan.imemodule.expression.ExpressionRecommendationResolver
import com.yuyan.imemodule.expression.ExpressionSync
import com.yuyan.imemodule.expression.model.EmojiCombination
import com.yuyan.imemodule.expression.model.ExpressionAsset
import com.yuyan.imemodule.expression.render.ExpressionRenderPolicy
import com.yuyan.imemodule.expression.render.ExpressionRenderer
import com.yuyan.imemodule.expression.send.ExpressionContentSender
import com.yuyan.imemodule.expression.send.ExpressionFlowController
import com.yuyan.imemodule.expression.send.ExpressionSendController
import com.yuyan.imemodule.expression.send.ExpressionSendResult
import com.yuyan.imemodule.expression.send.PreparedExpression
import com.yuyan.imemodule.expression.ui.ExpressionPanel
import com.yuyan.imemodule.keyboard.container.CandidatesContainer
import com.yuyan.imemodule.keyboard.container.ClipBoardContainer
import com.yuyan.imemodule.keyboard.container.SymbolContainer
import com.yuyan.imemodule.keyboard.container.SettingsContainer
import com.yuyan.imemodule.keyboard.container.T9TextContainer
import com.yuyan.imemodule.manager.InputModeSwitcher
import com.yuyan.imemodule.prefs.AppPrefs.Companion.getInstance
import com.yuyan.imemodule.prefs.behavior.KeyboardOneHandedMod
import com.yuyan.imemodule.prefs.behavior.PopupMenuMode
import com.yuyan.imemodule.prefs.behavior.SkbMenuMode
import com.yuyan.imemodule.service.DecodingInfo
import com.yuyan.imemodule.service.ImeService
import com.yuyan.imemodule.singleton.EnvironmentSingleton
import com.yuyan.imemodule.utils.DevicesUtils
import com.yuyan.imemodule.utils.InputMethodUtil
import com.yuyan.imemodule.utils.KeyboardLoaderUtil
import com.yuyan.imemodule.utils.LogUtil
import com.yuyan.imemodule.utils.StringUtils
import com.yuyan.imemodule.view.CandidatesBar
import com.yuyan.imemodule.view.EditPhrasesView
import com.yuyan.imemodule.view.FullDisplayKeyboardBar
import com.yuyan.imemodule.view.popup.PopupComponent
import com.yuyan.imemodule.view.preference.ManagedPreference
import com.yuyan.imemodule.view.widget.LifecycleRelativeLayout
import com.yuyan.inputmethod.CustomEngine
import com.yuyan.inputmethod.core.CandidateListItem
import com.yuyan.inputmethod.core.Kernel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import splitties.views.bottomPadding
import splitties.views.rightPadding
import java.io.File
import kotlin.math.absoluteValue

/**
 * 输入法主界面。
 * 包含拼音显示、候选词栏、键盘界面等。
 */
internal data class ExpressionLayoutBudget(
    val availableHeightPx: Int,
    val reservedNonPanelHeightPx: Int,
    val navigationInsetBottomPx: Int,
    val topObstructionPx: Int = 0,
    val bottomExtraObstructionPx: Int = 0,
)

private data class ExpressionStableViewport(
    val heightPx: Int,
    val obstructions: ExpressionSystemObstructions,
)

private data class ExpressionSystemInsetEdges(
    val statusBarTopPx: Int,
    val cutoutTopPx: Int,
    val cutoutBottomPx: Int,
    val nonNavigationBottomPx: Int = 0,
) {
    fun mergeWithVisibleNavigation(visibleNavigationBottomPx: Int): ExpressionSystemObstructions =
        mergeExpressionSystemObstructions(
            statusBarTopPx = statusBarTopPx,
            cutoutTopPx = cutoutTopPx,
            navigationBottomPx = visibleNavigationBottomPx,
            cutoutBottomPx = cutoutBottomPx,
            nonNavigationBottomPx = nonNavigationBottomPx,
        )
}

@SuppressLint("ViewConstructor")
class InputView(context: Context, private val service: ImeService) : LifecycleRelativeLayout(context), IResponseKeyEvent {
    private val appPrefs = getInstance()
    private val clipboardItemTimeout = appPrefs.clipboard.clipboardItemTimeout.getValue()
    private var chinesePrediction = true
    var isAddPhrases = false
    private val mChoiceNotifier = ChoiceNotifier()
    var mSkbRoot: RelativeLayout
    var mSkbCandidatesBarView: CandidatesBar
    private var mHoderLayoutLeft: LinearLayout
    private var mHoderLayoutRight: LinearLayout
    private lateinit var mOnehandHoderLayout: LinearLayout
    var mAddPhrasesLayout: EditPhrasesView
    private var mLlKeyboardBottomHolder: LinearLayout
    private var mInputKeyboardContainer: RelativeLayout
    private lateinit var mRightPaddingKey: ManagedPreference.PInt
    private lateinit var mBottomPaddingKey: ManagedPreference.PInt
    private var mFullDisplayKeyboardBar: FullDisplayKeyboardBar? = null
    private var expressionScope = newExpressionScope()
    private val chatEditorGate = ChatEditorGate()
    private var expressionPanelState = ExpressionPanelState(chatEditor = false)
    private lateinit var expressionPanel: ExpressionPanel
    private lateinit var expressionQueryCoordinator: ExpressionQueryCoordinator
    private lateinit var expressionManualSearch: ExpressionManualSearch
    internal var expressionComposingTextSource = ExpressionComposingTextSource.fromEngine()
    private val expressionInputTargetTracker = ExpressionInputTargetTracker()
    private var expressionInputSessionActive = true
    private var expressionResourcesDisposed = false
    private lateinit var expressionFlow: ExpressionFlowController
    private lateinit var expressionRecommendationResolver: ExpressionRecommendationResolver
    private var expressionSync: ExpressionSync? = null
    private var expressionSearchJob: Job? = null
    private var expressionPreviewJob: Job? = null
    private var expressionDownloadJob: Job? = null
    private var expressionPreparationJob: Job? = null
    private var expressionKeyboardVisibility: Pair<Int, Int>? = null
    private var expressionRequestId = 0L
    internal var expressionLayoutBudget = ExpressionLayoutBudget(0, 0, 0)
        private set
    private var latestExpressionInsetEdges: ExpressionSystemInsetEdges? = null
    private val expressionLayoutRefresh = Runnable { refreshExpressionLayoutBudget() }
    var hasSelection = false
    var hasSelectionAll = false
    // 记录删除内容
    private val textBeforeCursors = StringQueue(50)

    private fun newExpressionScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        LogUtil.d("1111111111111", "InputView init")
        initNavbarBackground(service)
        InputModeSwitcher.reset()
        mSkbRoot = LayoutInflater.from(context).inflate(R.layout.sdk_skb_container, this, false) as RelativeLayout
        addView(mSkbRoot)
        mSkbCandidatesBarView = mSkbRoot.findViewById(R.id.candidates_bar)
        mHoderLayoutLeft = mSkbRoot.findViewById(R.id.ll_skb_holder_layout_left)
        mHoderLayoutRight = mSkbRoot.findViewById(R.id.ll_skb_holder_layout_right)
        mInputKeyboardContainer = mSkbRoot.findViewById(R.id.ll_input_keyboard_container)
        mAddPhrasesLayout = EditPhrasesView(context)
        mLlKeyboardBottomHolder = mSkbRoot.findViewById(R.id.iv_keyboard_holder)
        KeyboardManager.instance.setData(mSkbRoot.findViewById(R.id.skb_input_keyboard_view), this)
        initExpressionPanel()
        PopupComponent.get().root.let { root ->
            root.parent?.let { (it as ViewGroup).removeView(root) }
            addView(root, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                addRule(ALIGN_BOTTOM, mSkbRoot.id)
                addRule(ALIGN_LEFT, mSkbRoot.id)
            })
        }
        DecodingInfo.candidatesLiveData.observe(this) { candidates ->
            updateCandidateBar()
            (KeyboardManager.instance.currentContainer as? CandidatesContainer)?.showCandidatesView()
            if (expressionQueryCoordinator.onCandidatesChanged(
                    candidates.firstOrNull()?.text,
                    DecodingInfo.isAssociate,
                )) {
                clearExpressionQuery()
            }
        }
        initView(context)
    }

    private fun initExpressionPanel() {
        expressionPanel = mSkbRoot.findViewById(R.id.expression_panel)
        refreshExpressionLayoutBudget()
        val aiStickerPreference = getInstance().internal.aiStickerEnabled
        expressionPanelState.setAiStickerEnabled(aiStickerPreference.getValue())
        val localCatalog = runCatching { ExpressionCatalog.fromAssets(context) }.getOrNull()
        if (localCatalog != null) {
            val cache = ExpressionCache(context.cacheDir)
            val sync = ExpressionSync(
                client = OkHttpClient(),
                baseUrl = ServerConfig.baseUrl,
                deviceId = DataCollector.deviceId(context),
                initialCatalog = localCatalog,
                cache = cache,
                scope = expressionScope,
            )
            expressionSync = sync
            val contentSender = ExpressionContentSender(
                context = context,
                inputConnection = ::currentInputConnection,
                editorMimeTypes = ::currentEditorMimeTypes,
            )
            val sendController = ExpressionSendController(contentSender)
            val renderer = ExpressionRenderer(context.cacheDir)
            expressionRecommendationResolver = ExpressionRecommendationResolver(context.cacheDir) { asset ->
                resolveExpressionFile(
                    sync = sync,
                    cache = cache,
                    version = asset.version,
                    relativePath = asset.fileName,
                    sha256 = asset.sha256,
                    remoteUrl = asset.url,
                )
            }
            expressionFlow = ExpressionFlowController(
                sendController = sendController,
                prepareAsset = { asset, query -> prepareAsset(sync, cache, renderer, asset, query) },
                prepareCombination = { combination -> prepareCombination(sync, cache, combination) },
            )
            expressionPanel.onAiStickerEnabledChange = { enabled ->
                if (!enabled) setExpressionExpanded(false)
                aiStickerPreference.setValue(enabled)
                expressionPanelState.setAiStickerEnabled(enabled)
                if (!enabled) {
                    expressionQueryCoordinator.reset()
                    expressionSearchJob?.cancel()
                    expressionSearchJob = null
                    expressionPreviewJob?.cancel()
                    expressionPreviewJob = null
                    expressionDownloadJob?.cancel()
                    expressionDownloadJob = null
                    expressionPreparationJob?.cancel()
                    expressionPreparationJob = null
                    expressionPanel.resetEmojiSelection()
                }
                expressionPanel.render(expressionPanelState, sync.currentCatalog())
            }
            expressionPanel.onRecommendationVisibilityChange = { visible ->
                if (visible) {
                    expressionPanelState.restoreRecommendations()
                } else {
                    setExpressionExpanded(false)
                    expressionPanelState.hideRecommendations()
                }
                expressionPanel.render(expressionPanelState, sync.currentCatalog())
            }
            expressionPanel.onAnimationPreviewChange = { enabled ->
                Toast.makeText(
                    context,
                    if (enabled) "已开启动画预览" else "已关闭动画预览",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            expressionPanel.onClearCache = {
                listOf("expression", "expression-previews", "expression-composed").forEach { name ->
                    java.io.File(context.cacheDir, name).deleteRecursively()
                }
                Toast.makeText(context, "AI斗图缓存已清理", Toast.LENGTH_SHORT).show()
            }
            expressionPanel.onTabSelected = { tab ->
                expressionPanelState.selectTab(tab)
                expressionPanel.render(expressionPanelState, sync.currentCatalog())
            }
            expressionPanel.onExpandRequested = { setExpressionExpanded(true) }
            expressionPanel.onAssetClick = { asset -> sendDirectly(asset) }
            expressionPanel.onEmojiCombinationClick = { combination, _ ->
                sendDirectly(combination)
            }
            expressionPanel.onEmojiCombinationMissing = { combination, deliver ->
                val remoteUrl = combination.url
                if (remoteUrl == null) {
                    deliver(null)
                } else {
                    expressionDownloadJob?.cancel()
                    expressionDownloadJob = expressionScope.launch {
                        val url = if (remoteUrl.startsWith("http://") || remoteUrl.startsWith("https://")) {
                            remoteUrl
                        } else {
                            ServerConfig.baseUrl + remoteUrl
                        }
                        deliver(
                            sync.download(
                                version = combination.version,
                                relativePath = combination.fileName,
                                url = url,
                                sha256 = combination.sha256,
                            ),
                        )
                    }
                }
            }
            expressionScope.launch(Dispatchers.IO) { sync.refreshCatalog() }
            expressionPanel.render(expressionPanelState, sync.currentCatalog())
        }
        expressionQueryCoordinator = ExpressionQueryCoordinator(
            scope = expressionScope,
            debounceMillis = 180,
            publishQuery = ::searchExpressions,
        )
        expressionManualSearch = ExpressionManualSearch(
            showMissingText = {
                Toast.makeText(
                    context,
                    R.string.expression_manual_search_missing_text,
                    Toast.LENGTH_SHORT,
                ).show()
            },
            preparePanel = ::prepareExpressionPanelForManualSearch,
            searchImmediately = { query -> expressionQueryCoordinator.searchImmediately(query) },
        )
        bindHostTextListeners()
    }

    private suspend fun prepareAsset(
        sync: ExpressionSync,
        cache: ExpressionCache,
        renderer: ExpressionRenderer,
        asset: ExpressionAsset,
        query: String,
    ): PreparedExpression {
        val source = resolveExpressionFile(
            sync = sync,
            cache = cache,
            version = asset.version,
            relativePath = asset.fileName,
            sha256 = asset.sha256,
            remoteUrl = asset.url,
        )
        val output = if (ExpressionRenderPolicy.shouldOverlayText(asset, query)) {
            renderer.render(asset, source, query)
        } else {
            source
        }
        return PreparedExpression(
            file = output,
            mimeType = ExpressionContentSender.mimeOf(asset.format),
        )
    }

    private suspend fun prepareCombination(
        sync: ExpressionSync,
        cache: ExpressionCache,
        combination: EmojiCombination,
    ): PreparedExpression = PreparedExpression(
        file = resolveExpressionFile(
            sync = sync,
            cache = cache,
            version = combination.version,
            relativePath = combination.fileName,
            sha256 = combination.sha256,
            remoteUrl = combination.url,
        ),
        mimeType = "image/webp",
    )

    private suspend fun resolveExpressionFile(
        sync: ExpressionSync,
        cache: ExpressionCache,
        version: String,
        relativePath: String,
        sha256: String,
        remoteUrl: String?,
    ): File = withContext(Dispatchers.IO) {
        cache.validFile(version, relativePath, sha256)?.let { return@withContext it }
        val builtIn = runCatching {
            context.assets.open("expression/$relativePath").use { input ->
                cache.writeVerified(version, relativePath, sha256, input)
            }
        }.getOrNull()
        if (builtIn != null) return@withContext builtIn
        val url = remoteUrl ?: error("素材尚未下载")
        val absoluteUrl = if (url.startsWith("http://") || url.startsWith("https://")) {
            url
        } else {
            ServerConfig.baseUrl + url
        }
        sync.download(version, relativePath, absoluteUrl, sha256)
            ?: error("素材下载失败")
    }

    private fun sendDirectly(asset: ExpressionAsset) {
        val query = expressionPanelState.query ?: return
        if (expressionPreparationJob?.isActive == true) return
        expressionPreparationJob = expressionScope.launch {
            showExpressionSendResult(expressionFlow.prepareAndSend(asset, query))
        }
    }

    private fun sendDirectly(combination: EmojiCombination) {
        if (expressionPreparationJob?.isActive == true) return
        expressionPreparationJob = expressionScope.launch {
            showExpressionSendResult(expressionFlow.prepareAndSend(combination))
        }
    }

    private fun showExpressionSendResult(result: ExpressionSendResult) {
        val message = when (result) {
            ExpressionSendResult.Sent,
            ExpressionSendResult.AlreadySending,
            -> null
            ExpressionSendResult.UnsupportedTarget -> "当前应用不支持图片发送"
            is ExpressionSendResult.Failed -> result.reason.ifBlank { "图片发送失败" }
            ExpressionSendResult.NotPrepared -> "图片发送失败"
        }
        message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }

    private fun clearExpressionQuery() {
        setExpressionExpanded(false)
        expressionSearchJob?.cancel()
        expressionSearchJob = null
        expressionPreviewJob?.cancel()
        expressionPreviewJob = null
        expressionPreparationJob?.cancel()
        expressionPreparationJob = null
        expressionPanelState.clear()
        expressionSync?.let { expressionPanel.render(expressionPanelState, it.currentCatalog()) }
    }

    private fun searchExpressions(query: String) {
        val sync = expressionSync ?: return
        if (!expressionPanelState.chatEditor) return
        setExpressionExpanded(false)
        val requestId = ++expressionRequestId
        expressionPanelState.beginQuery(query, requestId)
        expressionPanel.render(expressionPanelState, sync.currentCatalog())
        expressionSearchJob?.cancel()
        expressionPreviewJob?.cancel()
        expressionSearchJob = sync.search(
            query = query,
            requestId = requestId,
            acceptResponse = expressionPanelState::acceptResponse,
        ) { results ->
            expressionPreviewJob?.cancel()
            expressionPreviewJob = expressionScope.launch {
                val resolved = expressionRecommendationResolver.resolve(results, query)
                if (expressionPanelState.applyResults(requestId, resolved)) {
                    expressionPanel.render(expressionPanelState, sync.currentCatalog())
                }
            }
        }
    }

    private fun prepareExpressionPanelForManualSearch() {
        val aiStickerPreference = getInstance().internal.aiStickerEnabled
        aiStickerPreference.setValue(true)
        expressionPanelState.setAiStickerEnabled(true)
        expressionPanelState.collapse()
        expressionSync?.let { expressionPanel.render(expressionPanelState, it.currentCatalog()) }
    }

    /** 工具栏 AI 斗图手动搜索入口。 */
    fun searchExpressionsManually() {
        expressionManualSearch.perform(
            activeComposingText = expressionComposingTextSource.currentText(
                mSkbCandidatesBarView.getActiveCandNo(),
            ),
            panelLastQuery = expressionPanelState.query,
        )
    }

    private fun setExpressionExpanded(expanded: Boolean) {
        val sync = expressionSync ?: return
        val candidates = mSkbRoot.findViewById<View>(R.id.candidates_bar)
        val keyboard = mSkbRoot.findViewById<View>(R.id.skb_input_keyboard_view)
        if (expanded) {
            if (expressionPanelState.presentation == ExpressionPanelPresentation.EXPANDED) return
            expressionPanelState.expand()
            if (expressionPanelState.presentation != ExpressionPanelPresentation.EXPANDED) return
            expressionKeyboardVisibility = candidates.visibility to keyboard.visibility
            expressionPanel.setExpandedContentHeight(candidates.height + keyboard.height)
            candidates.visibility = View.GONE
            keyboard.visibility = View.GONE
        } else {
            expressionPanelState.collapse()
            expressionKeyboardVisibility?.let { (candidatesVisibility, keyboardVisibility) ->
                candidates.visibility = candidatesVisibility
                keyboard.visibility = keyboardVisibility
            }
            expressionKeyboardVisibility = null
        }
        service.setExpressionBackHandlingEnabled(
            expressionPanelState.presentation == ExpressionPanelPresentation.EXPANDED,
        )
        expressionPanel.render(expressionPanelState, sync.currentCatalog())
    }

    internal fun collapseExpressionForToolSwitch() {
        if (expressionPanelState.presentation == ExpressionPanelPresentation.EXPANDED) {
            setExpressionExpanded(false)
        }
    }

    fun handleExpressionBack(): Boolean {
        if (expressionSync == null) return false
        if (expressionPanelState.presentation != ExpressionPanelPresentation.EXPANDED) {
            return false
        }
        setExpressionExpanded(false)
        return true
    }

    /** 系统返回优先关闭 IME 内部临时面板，再交还系统隐藏键盘。 */
    fun handleImePanelBack(): Boolean {
        if (handleExpressionBack()) return true
        return (KeyboardManager.instance.currentContainer as? SettingsContainer)
            ?.handleQuickSettingsBack() == true
    }

    /** 工具栏快捷入口再次点击时切换面板显示状态。 */
    fun toggleQuickKeyboardSettings() {
        val current = KeyboardManager.instance.currentContainer as? SettingsContainer
        if (current?.isQuickSettingsVisible == true) {
            current.toggleQuickSettingsView()
            return
        }
        val symbolPage = (KeyboardManager.instance.currentContainer as? SymbolContainer)?.quickSymbolPage
        KeyboardManager.instance.switchKeyboard(KeyboardManager.KeyboardType.SETTINGS)
        (KeyboardManager.instance.currentContainer as? SettingsContainer)?.showQuickSettingsView(symbolPage)
        service.setExpressionBackHandlingEnabled(true)
        updateCandidateBar()
    }

    internal fun onQuickKeyboardSettingsClosed() {
        service.setExpressionBackHandlingEnabled(false)
    }

    @SuppressLint("ClickableViewAccessibility")
    fun initView(context: Context) {
        LogUtil.d("1111111111111", "InputView initView")
        if (isAddPhrases) {
            if (mAddPhrasesLayout.parent == null) {
                addView(mAddPhrasesLayout, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    addRule(ABOVE, mSkbRoot.id)
                    addRule(ALIGN_LEFT, mSkbRoot.id)
                })
                mAddPhrasesLayout.handleAddPhrasesView()
            }
        } else {
            removeView(mAddPhrasesLayout)
        }
        mSkbCandidatesBarView.initialize(mChoiceNotifier)
        val env = EnvironmentSingleton.instance
        val keyboardSetting = appPrefs.keyboardSetting
        val oneHandedModSwitch = keyboardSetting.oneHandedModSwitch.getValue()
        val oneHandedMod = keyboardSetting.oneHandedMod.getValue()
        if (::mOnehandHoderLayout.isInitialized) mOnehandHoderLayout.visibility = GONE
        if (oneHandedModSwitch) {
            mOnehandHoderLayout = if (oneHandedMod == KeyboardOneHandedMod.LEFT) mHoderLayoutRight else mHoderLayoutLeft
            mOnehandHoderLayout.apply {
                visibility = VISIBLE
                get(0).setOnClickListener { onClick(it) }
                get(1).setOnClickListener { onClick(it) }
                (get(1) as ImageButton).setImageResource(
                    if (oneHandedMod == KeyboardOneHandedMod.LEFT) R.drawable.ic_menu_one_hand_right else R.drawable.ic_menu_one_hand
                )
                layoutParams = layoutParams.apply {
                    width = env.holderWidth
                    height = env.skbHeight
                }
            }
        }
        mLlKeyboardBottomHolder.removeAllViews()
        mLlKeyboardBottomHolder.layoutParams.width = env.skbWidth
        mInputKeyboardContainer.layoutParams.width = env.inputAreaWidth
        if (env.keyboardModeFloat) {
            val isLand = env.isLandscape
            val internal = appPrefs.internal
            mBottomPaddingKey = if (isLand) internal.keyboardBottomPaddingLandscapeFloat else internal.keyboardBottomPaddingFloat
            mRightPaddingKey = if (isLand) internal.keyboardRightPaddingLandscapeFloat else internal.keyboardRightPaddingFloat

            bottomPadding = mBottomPaddingKey.getValue()
            rightPadding = mRightPaddingKey.getValue()
            mSkbRoot.bottomPadding = 0
            mSkbRoot.rightPadding = 0

            mLlKeyboardBottomHolder.minimumHeight = env.heightForKeyboardMove
            val mIvKeyboardMove = ImageView(context).apply {
                setImageResource(R.drawable.ic_horizontal_line)
                isClickable = true
                isEnabled = true
            }
            mLlKeyboardBottomHolder.addView(mIvKeyboardMove)
            mIvKeyboardMove.setOnTouchListener { _, event -> onMoveKeyboardEvent(event) }
        } else {
            val fullDisplayEnable = appPrefs.internal.fullDisplayKeyboardEnable.getValue()
            if (fullDisplayEnable && !env.isLandscape) {
                mFullDisplayKeyboardBar = FullDisplayKeyboardBar(context, this)
                mLlKeyboardBottomHolder.addView(mFullDisplayKeyboardBar)
                mLlKeyboardBottomHolder.minimumHeight = env.heightForFullDisplayBar + env.systemNavbarWindowsBottom
            } else {
                mLlKeyboardBottomHolder.minimumHeight = env.systemNavbarWindowsBottom
            }
            bottomPadding = 0
            rightPadding = 0
            mBottomPaddingKey = appPrefs.internal.keyboardBottomPadding
            mRightPaddingKey = appPrefs.internal.keyboardRightPadding
            mSkbRoot.bottomPadding = mBottomPaddingKey.getValue()
            mSkbRoot.rightPadding = mRightPaddingKey.getValue()
        }
        updateTheme()
        mLlKeyboardBottomHolder.minimumHeight = bottomHolderMinimumHeight(env)
        refreshExpressionLayoutBudget()
        scheduleExpressionLayoutBudgetRefresh()
    }

    private fun bottomHolderMinimumHeight(env: EnvironmentSingleton): Int = when {
        env.keyboardModeFloat -> env.heightForKeyboardMove
        appPrefs.internal.fullDisplayKeyboardEnable.getValue() && !env.isLandscape ->
            env.heightForFullDisplayBar + env.systemNavbarWindowsBottom
        else -> env.systemNavbarWindowsBottom
    }

    /**
     * 使用真实宿主高度和非面板子视图的测量值计算紧凑面板预算。
     * 候选栏已包含在 inputAreaHeight 中，因此测量值与环境回退值二选一，避免重复计数。
     */
    internal fun refreshExpressionLayoutBudget() {
        if (!::expressionPanel.isInitialized) return
        val env = EnvironmentSingleton.instance
        val viewport = stableExpressionViewport(env)
        val candidates = mSkbRoot.findViewById<View>(R.id.candidates_bar)
        val keyboard = mSkbRoot.findViewById<View>(R.id.skb_input_keyboard_view)
        val measuredInputArea = if (candidates.measuredHeight > 0 && keyboard.measuredHeight > 0) {
            candidates.measuredHeight + keyboard.measuredHeight
        } else {
            env.inputAreaHeight
        }
        val holderHeight = maxOf(
            mLlKeyboardBottomHolder.measuredHeight,
            mLlKeyboardBottomHolder.minimumHeight,
            bottomHolderMinimumHeight(env),
        )
        val ordinaryRootPadding = if (env.keyboardModeFloat) 0 else mSkbRoot.paddingBottom
        val floatingOffsetPadding = if (env.keyboardModeFloat) paddingBottom else 0
        val reserved = (measuredInputArea.toLong() + holderHeight + ordinaryRootPadding +
            floatingOffsetPadding).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        expressionLayoutBudget = ExpressionLayoutBudget(
            availableHeightPx = viewport.heightPx,
            reservedNonPanelHeightPx = reserved,
            navigationInsetBottomPx = env.systemNavbarWindowsBottom.coerceAtLeast(0),
            topObstructionPx = viewport.obstructions.topPx,
            bottomExtraObstructionPx = viewport.obstructions.bottomExtraPx,
        )
        expressionPanel.setAvailableLayoutHeight(
            availableHeightPx = expressionLayoutBudget.availableHeightPx,
            reservedKeyboardHeightPx = expressionLayoutBudget.reservedNonPanelHeightPx,
        )
    }

    /**
     * 视口必须独立于 WRAP_CONTENT 的 IME 层级，否则上一次面板高度会反向成为下一轮上限。
     * 底部导航区由 holder 在 reserved 中计数，因此这里只扣顶部状态栏和上下刘海，避免导航重复扣减。
     */
    private fun stableExpressionViewport(env: EnvironmentSingleton): ExpressionStableViewport {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        var realBoundsHeight = 0
        var metricsInsetEdges: ExpressionSystemInsetEdges? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && windowManager != null) {
            runCatching {
                val metrics = windowManager.currentWindowMetrics
                realBoundsHeight = metrics.bounds.height()
                val status = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars())
                val cutout = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout())
                metricsInsetEdges = ExpressionSystemInsetEdges(
                    statusBarTopPx = status.top,
                    cutoutTopPx = cutout.top,
                    cutoutBottomPx = cutout.bottom,
                )
            }
        }
        if (realBoundsHeight <= 0 && windowManager != null) {
            @Suppress("DEPRECATION")
            val realMetrics = DisplayMetrics().also(windowManager.defaultDisplay::getRealMetrics)
            realBoundsHeight = realMetrics.heightPixels
        }
        if (realBoundsHeight <= 0) {
            realBoundsHeight = maxOf(resources.displayMetrics.heightPixels, env.mScreenHeight)
        }
        val insetEdges = latestExpressionInsetEdges
            ?: platformRootExpressionInsetEdges()
            ?: metricsInsetEdges
            ?: fallbackExpressionInsetEdges()
        // 必须与 bottom holder 使用同一份“当前可见导航栏”高度，避免隐藏导航栏时少扣刘海。
        val obstructions = insetEdges.mergeWithVisibleNavigation(env.systemNavbarWindowsBottom)
        return ExpressionStableViewport(
            heightPx = obstructions.stableViewportHeight(realBoundsHeight.coerceAtLeast(0)),
            obstructions = obstructions,
        )
    }

    private fun platformRootExpressionInsetEdges(): ExpressionSystemInsetEdges? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        val insets = rootWindowInsets ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val status = insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars())
            val cutout = insets.getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout())
            return ExpressionSystemInsetEdges(
                statusBarTopPx = status.top,
                cutoutTopPx = cutout.top,
                cutoutBottomPx = cutout.bottom,
            )
        }
        @Suppress("DEPRECATION")
        val statusTop = insets.systemWindowInsetTop
        val cutoutInsets = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            displayCutoutSafeInsets(insets.displayCutout)
        } else {
            0 to 0
        }
        return ExpressionSystemInsetEdges(
            statusBarTopPx = statusTop,
            cutoutTopPx = cutoutInsets.first,
            cutoutBottomPx = cutoutInsets.second,
        )
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun displayCutoutSafeInsets(cutout: DisplayCutout?): Pair<Int, Int> =
        (cutout?.safeInsetTop ?: 0) to (cutout?.safeInsetBottom ?: 0)

    private fun compatExpressionInsetEdges(insets: WindowInsetsCompat): ExpressionSystemInsetEdges {
        fun stableInsets(typeMask: Int) = runCatching { insets.getInsetsIgnoringVisibility(typeMask) }
            .getOrElse { insets.getInsets(typeMask) }
        val status = stableInsets(WindowInsetsCompat.Type.statusBars())
        val cutout = stableInsets(WindowInsetsCompat.Type.displayCutout())
        return ExpressionSystemInsetEdges(
            statusBarTopPx = status.top,
            cutoutTopPx = cutout.top,
            cutoutBottomPx = cutout.bottom,
        )
    }

    private fun fallbackExpressionInsetEdges(): ExpressionSystemInsetEdges {
        val statusBarId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBarTop = if (statusBarId != 0) resources.getDimensionPixelSize(statusBarId) else 0
        return ExpressionSystemInsetEdges(
            statusBarTopPx = statusBarTop,
            cutoutTopPx = 0,
            cutoutBottomPx = 0,
        )
    }

    private fun scheduleExpressionLayoutBudgetRefresh() {
        removeCallbacks(expressionLayoutRefresh)
        post(expressionLayoutRefresh)
    }

    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var rightPaddingValue = 0
    private var bottomPaddingValue = 0
    private var mSkbRootHeight = 0
    private var mSkbRootWidth = 0

    private fun onMoveKeyboardEvent(event: MotionEvent?): Boolean {
        when (event?.action) {
            MotionEvent.ACTION_DOWN -> {
                bottomPaddingValue = mBottomPaddingKey.getValue()
                rightPaddingValue = mRightPaddingKey.getValue()
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                mSkbRootHeight = mSkbRoot.height
                mSkbRootWidth = mSkbRoot.width
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY
                val env = EnvironmentSingleton.instance

                if (dx.absoluteValue > 10) {
                    rightPaddingValue = (rightPaddingValue - dx.toInt()).coerceIn(0, this.width - mSkbRootWidth)
                    initialTouchX = event.rawX
                    if (env.keyboardModeFloat) rightPadding = rightPaddingValue else mSkbRoot.rightPadding = rightPaddingValue
                }
                if (dy.absoluteValue > 10) {
                    bottomPaddingValue = (bottomPaddingValue - dy.toInt()).coerceIn(0, this.height - mSkbRootHeight)
                    initialTouchY = event.rawY
                    if (env.keyboardModeFloat) bottomPadding = bottomPaddingValue else mSkbRoot.bottomPadding = bottomPaddingValue
                    refreshExpressionLayoutBudget()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mRightPaddingKey.setValue(rightPaddingValue)
                mBottomPaddingKey.setValue(bottomPaddingValue)
            }
        }
        return false
    }

    fun updateTheme() {
        LogUtil.d("1111111111111", "InputView updateTheme")
        setBackgroundResource(android.R.color.transparent)
        val activeTheme = ThemeManager.activeTheme
        val keyTextColor = activeTheme.keyTextColor
        val env = EnvironmentSingleton.instance

        val background = activeTheme.backgroundDrawable(ThemeManager.prefs.keyBorder.getValue())
        if (background is BitmapDrawable) {
            val scaledBitmap = background.bitmap.scale(env.skbWidth, env.inputAreaHeight)
            mSkbRoot.background = scaledBitmap.toDrawable(context.resources).apply {
                colorFilter = background.colorFilter
            }
        } else {
            mSkbRoot.background = background
        }
        mSkbCandidatesBarView.updateTheme(keyTextColor)
        if (::mOnehandHoderLayout.isInitialized) {
            (mOnehandHoderLayout[0] as ImageButton).drawable?.setTint(keyTextColor)
            (mOnehandHoderLayout[1] as ImageButton).drawable?.setTint(keyTextColor)
        }
        mFullDisplayKeyboardBar?.updateTheme(keyTextColor)
        mAddPhrasesLayout.updateTheme(activeTheme)
        if (::expressionPanel.isInitialized) {
            expressionPanel.updateTheme()
        }
    }

    private fun onClick(view: View) {
        val keyboardSetting = appPrefs.keyboardSetting
        if (view.id == R.id.ib_holder_one_hand_none) {
            keyboardSetting.oneHandedModSwitch.setValue(!keyboardSetting.oneHandedModSwitch.getValue())
        } else {
            val currentMod = keyboardSetting.oneHandedMod.getValue()
            keyboardSetting.oneHandedMod.setValue(if (currentMod == KeyboardOneHandedMod.LEFT) KeyboardOneHandedMod.RIGHT else KeyboardOneHandedMod.LEFT)
        }
        EnvironmentSingleton.instance.initData()
        KeyboardLoaderUtil.instance.clearKeyboardMap()
        KeyboardManager.instance.apply {
            clearKeyboard()
            switchKeyboard()
        }
    }

    override fun responseLongKeyEvent(result: Pair<PopupMenuMode, String>) {
        val (mode, value) = result
        if (mode != PopupMenuMode.None && !DecodingInfo.isAssociate && !DecodingInfo.isCandidatesEmpty) {
            if (InputModeSwitcher.isChinese || InputModeSwitcher.isEnglish) chooseAndUpdate()
        }

        when (mode) {
            PopupMenuMode.Text -> if (SymbolPreset.containsKey(value)) commitPairSymbol(value) else commitText(value)
            PopupMenuMode.SwitchIME -> InputMethodUtil.showPicker()
            PopupMenuMode.EMOJI -> onSettingsMenuClick(SkbMenuMode.Emojicon)
            PopupMenuMode.EnglishCell -> {
                val pref = appPrefs.input.abcSearchEnglishCell
                pref.setValue(!pref.getValue())
                KeyboardManager.instance.switchKeyboard()
            }
            PopupMenuMode.Clear -> {
                if (isAddPhrases) mAddPhrasesLayout.clearPhrasesContent()
                else service.getTextBeforeCursor(1000).takeIf { it.isNotEmpty() }?.let {
                    textBeforeCursors.push(it)
                    service.deleteSurroundingText(1000)
                }
            }
            PopupMenuMode.Revertl -> textBeforeCursors.popInReverseOrder()?.takeIf { it.isNotEmpty() }?.let { commitText(it) }
            PopupMenuMode.Enter -> commitText("\n")
            else -> {}
        }
        if (mode == PopupMenuMode.Clear) resetToIdleState()
    }

    override fun responseHandwritingResultEvent(words: Array<CandidateListItem>) {
        DecodingInfo.cacheCandidates(words)
    }

    override fun responseKeyEvent(sKey: SoftKey) {
        val keyCode = sKey.code
        if(sKey.isUserDefKey)processUserDefKey(keyCode, sKey.keyLabel)
        else if(sKey.isUniStrKey){
            if (!DecodingInfo.isAssociate && !DecodingInfo.isCandidatesEmpty) chooseAndUpdate()
            sKey.label.takeIf(String::isNotEmpty)?.let {
                if (SymbolPreset.containsKey(it)) commitPairSymbol(it) else commitText(it)
            }
        } else {
            val metaState = when(Kernel.getCurrentRimeSchema()) {
                CustomConstant.SCHEMA_ZH_T9, CustomConstant.SCHEMA_ZH_STROKE, CustomConstant.SCHEMA_ZH_DOUBLE_LX17 -> KeyEvent.META_CAPS_LOCK_ON
                else -> InputModeSwitcher.mToggleStates.modifiers
            }
            processKeyUp(KeyEvent(0, 0, KeyEvent.ACTION_UP, keyCode, 0, metaState, 0, 0, KeyEvent.FLAG_SOFT_KEYBOARD))
        }
    }


    fun processKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) return true
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_APOSTROPHE, KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_BACK -> return true
        }
        return false
    }

    fun processKeyUp(event: KeyEvent): Boolean {
        if(event.isSystem) return processSystemKeys(event)
        else if(isFunctionKey(event.keyCode)){
            processFunctionKey(event)
            return true
        }
        InputModeSwitcher.resetCharCase()
        val englishCellDisable = InputModeSwitcher.isEnglish && !appPrefs.input.abcSearchEnglishCell.getValue()
        return when {
            englishCellDisable -> processEnglishKey(event)
            InputModeSwitcher.isEnglish || InputModeSwitcher.isChinese -> processInput(event)
            else -> processEnglishKey(event)
        }
//        return if(appPrefs.input.abcSearchEnglishCell.getValue() || InputModeSwitcherManager.isChinese)processInput(event) else processEnglishKey(event)
    }

    private fun processEnglishKey(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val keyChar = event.unicodeChar
        val label = keyChar.toChar().toString()
        var result = true
        when {
            keyCode == KeyEvent.KEYCODE_DEL -> {
                service.getTextBeforeCursor(1).takeIf { it.isNotEmpty() }?.let { textBeforeCursors.push(it) }
                sendKeyEvent(keyCode)
            }
            keyCode in (KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) -> {
                textBeforeCursors.clear()
                commitText(label)
            }
            keyCode != 0 -> sendKeyEvent(keyCode)
            label.isNotEmpty() -> if (SymbolPreset.containsKey(label)) commitPairSymbol(label) else commitText(label)
            else -> result = false
        }
        return result
    }

    // 系统按键只处理返回键，当点击返回键且软键盘显示时，隐藏键盘并消费事件
    private fun processSystemKeys(event: KeyEvent): Boolean {
        return when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> if (service.isInputViewShown) { requestHideSelf(); true } else false
            else -> false
        }
    }

    fun isFunctionKey(keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_CLEAR, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT, KeyEvent.KEYCODE_LANGUAGE_SWITCH, KeyEvent.KEYCODE_SYM,
            KeyEvent.KEYCODE_PICTSYMBOLS, KeyEvent.KEYCODE_NUM -> return true
        }
        return false
    }

    private fun processFunctionKey(event: KeyEvent) {
        when (val keyCode = event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_SPACE -> {
                if (DecodingInfo.isCandidatesEmpty || DecodingInfo.isAssociate) {
                    val directEnglish = InputModeSwitcher.isEnglish &&
                        !appPrefs.input.abcSearchEnglishCell.getValue()
                    if (keyCode == KeyEvent.KEYCODE_SPACE && directEnglish) {
                        commitText(" ")
                    } else {
                        sendKeyEvent(keyCode)
                    }
                    resetToIdleState()
                }
                else chooseAndUpdate()
            }
            KeyEvent.KEYCODE_CLEAR -> resetToIdleState()
            KeyEvent.KEYCODE_ENTER -> {
                if (DecodingInfo.isCandidatesEmpty || DecodingInfo.isAssociate) sendKeyEvent(keyCode)
                else commitCandidateAndNotify(DecodingInfo.composingStrForCommit)
                resetToIdleState()
            }
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> {
                if(InputModeSwitcher.isChinese && !DecodingInfo.isEngineFinish) processInput(KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_APOSTROPHE, 0, 0, 0, 0, KeyEvent.FLAG_SOFT_KEYBOARD))
                else InputModeSwitcher.processShiftKey(keyCode)
            }
        }
    }


    private fun processUserDefKey(keyCode: Int, label: String) {
        when {
            keyCode == InputModeSwitcher.USER_KEYCODE_CURSOR_DIRECTION -> {
                resetToIdleState()
                return
            }
            !DecodingInfo.isAssociate && !DecodingInfo.isCandidatesEmpty -> {
                if (InputModeSwitcher.isChinese || InputModeSwitcher.isEnglish) chooseAndUpdate()
            }
        }

        when (keyCode) {
            InputModeSwitcher.USER_KEYCODE_SYMBOL -> {
                KeyboardManager.instance.switchKeyboard(KeyboardManager.KeyboardType.SYMBOL)
                (KeyboardManager.instance.currentContainer as? SymbolContainer)?.setSymbolsView()
            }
            InputModeSwitcher.USER_KEYCODE_EMOJI -> onSettingsMenuClick(SkbMenuMode.Emojicon)
            in InputModeSwitcher.USER_KEYCODE_RETURN..InputModeSwitcher.USER_KEYCODE_LANG -> InputModeSwitcher.switchModeForUserKey(keyCode)
            in InputModeSwitcher.USER_KEYCODE_PASTE..InputModeSwitcher.USER_KEYCODE_CUT -> commitTextEditMenu(KeyPreset.textEditMenuPreset[keyCode])
            InputModeSwitcher.USER_KEYCODE_MOVE_START -> service.setSelection(0, if (hasSelection) selEnd else 0)
            InputModeSwitcher.USER_KEYCODE_MOVE_END -> {
                if (hasSelection) {
                    val start = selStart
                    commitTextEditMenu(KeyPreset.textEditMenuPreset[InputModeSwitcher.USER_KEYCODE_SELECT_ALL])
                    postDelayed(100) { service.setSelection(start, selEnd) }
                } else {
                    commitTextEditMenu(KeyPreset.textEditMenuPreset[InputModeSwitcher.USER_KEYCODE_SELECT_ALL])
                    postDelayed(100) { service.sendCombinationKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT)}
                }
            }
            InputModeSwitcher.USER_KEYCODE_SELECT_MODE -> {
                hasSelection = !hasSelection
                if (!hasSelection) service.sendCombinationKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT)
            }
            InputModeSwitcher.USER_KEYCODE_SELECT_ALL -> {
                hasSelectionAll = !hasSelectionAll
                if (!hasSelectionAll) service.sendCombinationKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT)
                else commitTextEditMenu(KeyPreset.textEditMenuPreset[keyCode])
            }
            else -> {
                if(label.isNotEmpty()){
                    if (SymbolPreset.containsKey(label)) commitPairSymbol(label) else commitText(label)
                }
            }
        }
    }

    private fun processInput(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val keyChar = event.unicodeChar
        val label = keyChar.toChar().toString()

        return when {
            keyCode == KeyEvent.KEYCODE_DEL -> {
                if (DecodingInfo.isCandidatesEmpty || DecodingInfo.isAssociate) {
                    service.getTextBeforeCursor(1).takeIf { it.isNotEmpty() }?.let { textBeforeCursors.push(it) }
                    sendKeyEvent(keyCode)
                } else {
                    DecodingInfo.deleteAction()
                    updateCandidate()
                }
                true
            }
            (Character.isLetterOrDigit(keyChar) && keyCode != KeyEvent.KEYCODE_0) || keyCode == KeyEvent.KEYCODE_APOSTROPHE || keyCode == KeyEvent.KEYCODE_SEMICOLON -> {
                textBeforeCursors.clear()
                DecodingInfo.inputAction(event)
                updateCandidate()
                true
            }
            keyCode != 0 -> {
                if (!DecodingInfo.isCandidatesEmpty && !DecodingInfo.isAssociate) chooseAndUpdate()
                sendKeyEvent(keyCode)
                resetToIdleState()
                true
            }
            label.isNotEmpty() -> {
                if (!DecodingInfo.isCandidatesEmpty && !DecodingInfo.isAssociate) chooseAndUpdate()
                if (SymbolPreset.containsKey(label)) commitPairSymbol(label) else commitText(label)
                true
            }
            else -> false
        }
    }

    fun resetToIdleState() {
        resetCandidateWindow()
        if (hasSelectionAll) hasSelectionAll = false
    }

    fun chooseAndUpdate(candId: Int = mSkbCandidatesBarView.getActiveCandNo()): String? {
        val candidate = DecodingInfo.getCandidate(candId)
        return if (candidate?.comment == "📋") {
            commitCandidateAndNotify(candidate.text)
            candidate.text
        } else if (candidate?.comment == CompletionSync.candidateComment) {
            // 服务端智能补全候选：直接上屏，并上报接受（供服务端统计接受率）
            commitCandidateAndNotify(candidate.text)
            CompletionSync.find(candidate.text)?.let { CompletionSync.reportAccepted(context, it) }
            candidate.text
        } else {
            val choice = DecodingInfo.chooseDecodingCandidate(candId)
            if (DecodingInfo.isCandidatesEmpty || DecodingInfo.isAssociate) {
                KeyboardManager.instance.switchKeyboard()
                (KeyboardManager.instance.currentContainer as? T9TextContainer)?.updateSymbolListView()
                commitCandidateAndNotify(choice)
                choice
            } else {
                if (!DecodingInfo.isCandidatesEmpty) {
                    (KeyboardManager.instance.currentContainer as? T9TextContainer)?.updateSymbolListView()
                    if (InputModeSwitcher.isEnglish) setComposingText(DecodingInfo.composingStrForCommit)
                } else {
                    resetToIdleState()
                }
                null
            }
        }
    }

    internal fun commitCandidateAndNotify(text: String?) {
        commitDecInfoText(text)
    }

    internal fun notifyExpressionTextCommitted(
        text: String,
        kind: ExpressionCommitKind = ExpressionCommitKind.COMPLETE,
    ) {
        expressionManualSearch.onHostCommitted(text, kind)?.let(expressionQueryCoordinator::onCommitted)
    }

    private fun notifyExpressionTextEdited() {
        expressionManualSearch.invalidateCommittedText()
        expressionQueryCoordinator.reset()
        clearExpressionQuery()
    }

    private fun bindHostTextListeners() {
        service.setHostTextCommitListener(
            owner = this,
            listener = ::notifyExpressionTextCommitted,
            editListener = ::notifyExpressionTextEdited,
        )
    }

    private fun activateExpressionInputSession() {
        if (expressionResourcesDisposed || expressionInputSessionActive) return
        expressionQueryCoordinator = ExpressionQueryCoordinator(
            scope = expressionScope,
            debounceMillis = 180,
            publishQuery = ::searchExpressions,
        )
        expressionInputSessionActive = true
        bindHostTextListeners()
    }

    private fun deactivateExpressionInputSession() {
        if (!expressionInputSessionActive) return
        expressionInputSessionActive = false
        service.clearHostTextCommitListener(this)
        expressionQueryCoordinator.close()
    }

    private fun updateCandidate() {
        DecodingInfo.updateDecodingCandidate()
        if (!DecodingInfo.isCandidatesEmpty) {
            (KeyboardManager.instance.currentContainer as? T9TextContainer)?.updateSymbolListView()
        } else {
            resetToIdleState()
        }
        if (InputModeSwitcher.isEnglish) setComposingText(DecodingInfo.composingStrForCommit)
    }

    fun updateCandidateBar() = mSkbCandidatesBarView.scheduleShowCandidates()

    /**
     * 语音输入入口转发（service 为构造私有参数，类外不可直接访问）
     */
    fun startVoiceInput() = service.startVoiceInput()

    /** 当前输入连接（斗图 commitContent 使用） */
    fun currentInputConnection(): android.view.inputmethod.InputConnection? = service.currentInputConnection

    /** 当前编辑器声明的 MIME 类型（判断是否支持图片输入） */
    fun currentEditorMimeTypes(): Array<String>? = service.currentInputEditorInfo?.contentMimeTypes

    private fun resetCandidateWindow() {
        DecodingInfo.reset()
        (KeyboardManager.instance.currentContainer as? T9TextContainer)?.updateSymbolListView()
    }

    inner class ChoiceNotifier internal constructor() : CandidateViewListener {
        override fun onClickChoice(choiceId: Int) {
            DevicesUtils.tryPlayKeyDown()
            DevicesUtils.tryVibrate(KeyboardManager.instance.currentContainer)
            chooseAndUpdate(choiceId)
        }

        override fun onClickMore(level: Int) {
            if (level == 0) {
                onSettingsMenuClick(SkbMenuMode.CandidatesMore)
            } else {
                KeyboardManager.instance.switchKeyboard()
                (KeyboardManager.instance.currentContainer as? T9TextContainer)?.updateSymbolListView()
            }
        }

        override fun onClickMenu(skbMenuMode: SkbMenuMode) = onSettingsMenuClick(skbMenuMode)

        override fun onClickClearCandidate() {
            resetToIdleState()
            KeyboardManager.instance.switchKeyboard()
        }

        override fun onClickClearClipBoard() {
            DataBaseKT.instance.clipboardDao().deleteAllExceptKeep()
            (KeyboardManager.instance.currentContainer as? ClipBoardContainer)?.showClipBoardView(SkbMenuMode.ClipBoard)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (expressionResourcesDisposed) {
            expressionScope = newExpressionScope()
            expressionResourcesDisposed = false
            expressionInputSessionActive = true
            initExpressionPanel()
        }
        refreshExpressionLayoutBudget()
        scheduleExpressionLayoutBudgetRefresh()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        refreshExpressionLayoutBudget()
        scheduleExpressionLayoutBudgetRefresh()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        refreshExpressionLayoutBudget()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        latestExpressionInsetEdges = null
        ViewCompat.requestApplyInsets(this)
        refreshExpressionLayoutBudget()
        scheduleExpressionLayoutBudgetRefresh()
    }

    override fun onDetachedFromWindow() {
        disposeExpressionResources()
        super.onDetachedFromWindow()
    }

    internal fun disposeExpressionResources() {
        if (expressionResourcesDisposed) return
        removeCallbacks(expressionLayoutRefresh)
        expressionResourcesDisposed = true
        expressionInputSessionActive = false
        service.setExpressionBackHandlingEnabled(false)
        service.clearHostTextCommitListener(this)
        expressionQueryCoordinator.close()
        expressionSearchJob?.cancel()
        expressionSearchJob = null
        expressionPreviewJob?.cancel()
        expressionPreviewJob = null
        expressionDownloadJob?.cancel()
        expressionDownloadJob = null
        expressionPreparationJob?.cancel()
        expressionPreparationJob = null
        expressionPanel.clearCallbacks()
        expressionSync = null
        expressionScope.cancel()
    }

    fun onSettingsMenuClick(skbMenuMode: SkbMenuMode, extra: Phrase? = null) {
        if (skbMenuMode == SkbMenuMode.AddPhrases) {
            isAddPhrases = true
            KeyboardManager.instance.switchKeyboard(InputModeSwitcher.skbImeLayout)
            initView(context)
            if (extra != null) {
                DataBaseKT.instance.phraseDao().deleteByContent(extra.content)
                mAddPhrasesLayout.setExtraData(extra)
            } else {
                mAddPhrasesLayout.clearPhrasesContent()
            }
        } else {
            onSettingsMenuClick(this, skbMenuMode)
        }
        mSkbCandidatesBarView.initMenuView()
    }

    fun selectPrefix(position: Int) {
        DevicesUtils.tryPlayKeyDown()
        DevicesUtils.tryVibrate(this)
        DecodingInfo.selectPrefix(position)
        updateCandidate()
    }

    fun showSymbols(symbols: Array<String>) {
        val list = symbols.map { CandidateListItem("📋", it) }.toTypedArray()
        DecodingInfo.cacheCandidates(list, true)
    }

    fun requestHideSelf() = service.requestHideSelf(0)

    private fun sendKeyEvent(keyCode: Int) {
        if (isAddPhrases) {
            mAddPhrasesLayout.sendKeyEvent(keyCode)
            if (keyCode == KeyEvent.KEYCODE_ENTER) {
                isAddPhrases = false
                initView(context)
                onSettingsMenuClick(SkbMenuMode.Phrases)
            }
        } else {
            when (keyCode) {
                KeyEvent.KEYCODE_ENTER -> service.sendEnterKeyEvent()
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> service.sendNumericKeyEventAndReport(keyCode)
                in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    service.sendCombinationKeyEvents(keyCode, shift = hasSelection)
                    if (hasSelectionAll) hasSelectionAll = false
                }
                else -> service.sendCombinationKeyEvents(keyCode)
            }
        }
    }

    private fun setComposingText(text: CharSequence) {
        if (!isAddPhrases) service.setComposingText(text)
    }

    private fun commitText(text: String) {
        if (isAddPhrases) mAddPhrasesLayout.commitText(text)
        else service.commitTextAndReport(
            StringUtils.converted2FlowerTypeface(text),
            kind = ExpressionCommitKind.INCREMENTAL,
        )
    }

    private fun commitPairSymbol(text: String) {
        if (isAddPhrases) {
            mAddPhrasesLayout.commitText(text)
        } else {
            if (appPrefs.input.symbolPairInput.getValue()) {
                service.commitText(text + SymbolPreset[text]!!)
                postDelayed(300) { service.sendCombinationKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT) }
            } else {
                service.commitText(text)
            }
        }
    }

    private fun commitTextEditMenu(id: Int?) {
        id?.let { service.commitTextEditMenu(it) }
    }

    fun performEditorAction(editorAction: Int) = service.performEditorAction(editorAction)

    private fun commitDecInfoText(resultText: String?): Boolean {
        resultText ?: return false
        if (isAddPhrases) {
            mAddPhrasesLayout.commitText(resultText)
            return false
        } else {
            val committedToHost = service.commitTextAndReport(
                StringUtils.converted2FlowerTypeface(resultText),
                kind = ExpressionCommitKind.COMPLETE,
            )
            if (committedToHost && InputModeSwitcher.isEnglish){
                service.finishComposingText()
                if(appPrefs.input.abcSpaceAuto.getValue()) service.commitText(" ")
                resetToIdleState()
            }
            return committedToHost
        }
    }

    private fun initNavbarBackground(service: ImeService) {
        service.window.window?.also { win ->
            WindowCompat.setDecorFitsSystemWindows(win, false)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                @Suppress("DEPRECATION")
                win.navigationBarColor = Color.TRANSPARENT
            } else {
                win.insetsController?.apply {
                    hide(WindowInsets.Type.navigationBars())
                    systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) win.isNavigationBarContrastEnforced = false
        }

        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val env = EnvironmentSingleton.instance
            env.systemNavbarWindowsBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            latestExpressionInsetEdges = compatExpressionInsetEdges(insets)
            mLlKeyboardBottomHolder.minimumHeight = bottomHolderMinimumHeight(env)
            refreshExpressionLayoutBudget()
            scheduleExpressionLayoutBudgetRefresh()
            insets
        }
    }

    fun onStartInputView(editorInfo: EditorInfo, restarting: Boolean) {
        InputModeSwitcher.requestInputWithSkb(editorInfo)
        onExpressionInputViewStarted(editorInfo, restarting, service.currentInputConnection)
        if (!restarting) {
            resetToIdleState()
            val clipboard = appPrefs.clipboard
            if (clipboard.clipboardSuggestion.getValue()) {
                val internal = appPrefs.internal
                val lastTime = internal.clipboardUpdateTime.getValue()
                if (System.currentTimeMillis() - lastTime <= clipboardItemTimeout * 1000) {
                    val content = internal.clipboardUpdateContent.getValue()
                    if (content.isNotBlank()) {
                        showSymbols(arrayOf(content))
                        internal.clipboardUpdateTime.setValue(0L)
                    }
                }
            }
        }
    }

    /** 可测的生产生命周期边界；同一目标 restart 不打断当前组合/斗图请求。 */
    internal fun onExpressionInputViewStarted(
        editorInfo: EditorInfo,
        restarting: Boolean,
        connectionIdentity: Any?,
    ) {
        activateExpressionInputSession()
        if (expressionInputTargetTracker.shouldReset(editorInfo, restarting, connectionIdentity)) {
            onExpressionInputTargetChanged(editorInfo)
        }
    }

    /** 输入连接/编辑器切换时的斗图会话边界，由 [onStartInputView] 调用。 */
    internal fun onExpressionInputTargetChanged(editorInfo: EditorInfo) {
        expressionComposingTextSource.clear()
        setExpressionExpanded(false)
        expressionManualSearch.resetSession()
        expressionQueryCoordinator.close()
        expressionQueryCoordinator = ExpressionQueryCoordinator(
            scope = expressionScope,
            debounceMillis = 180,
            publishQuery = ::searchExpressions,
        )
        expressionSearchJob?.cancel()
        expressionSearchJob = null
        expressionPreviewJob?.cancel()
        expressionPreviewJob = null
        expressionDownloadJob?.cancel()
        expressionDownloadJob = null
        expressionPreparationJob?.cancel()
        expressionPreparationJob = null
        expressionPanel.resetEmojiSelection()
        expressionPanelState = ExpressionPanelState(
            aiStickerEnabled = getInstance().internal.aiStickerEnabled.getValue(),
            chatEditor = chatEditorGate.allows(editorInfo.packageName, editorInfo),
        )
        expressionSync?.let { expressionPanel.render(expressionPanelState, it.currentCatalog()) }
    }

    fun onWindowShown() {
        chinesePrediction = appPrefs.input.chinesePrediction.getValue()
    }

    fun onWindowHidden() {
        if (isAddPhrases) {
            isAddPhrases = false
            mAddPhrasesLayout.addPhrasesHandle()
            initView(context)
        }
        onExpressionWindowHidden()
        KeyboardManager.instance.switchKeyboard()
        resetToIdleState()
    }

    /** 窗口隐藏时先封闭斗图通知/请求，防止晚到的语音或 commit 污染下次会话。 */
    internal fun onExpressionWindowHidden() {
        deactivateExpressionInputSession()
        expressionManualSearch.resetSession()
        expressionDownloadJob?.cancel()
        expressionDownloadJob = null
        clearExpressionQuery()
    }

    private var selStart = 0
    private var selEnd = 0
    private var oldCandidatesEnd = 0

    fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesEnd: Int) {
        selStart = newSelStart
        selEnd = newSelEnd
        if (InputModeSwitcher.isEnglish ) {
            if (oldCandidatesEnd == candidatesEnd) {
                service.finishComposingText()
                resetToIdleState()
            }
            oldCandidatesEnd = candidatesEnd
            return
        }
        if (oldSelStart == newSelStart) return
        when {
            InputModeSwitcher.isNumberSkb -> {
                val textBeforeCursor = service.getTextBeforeCursor(500)
                if (textBeforeCursor.isBlank()) resetCandidateWindow()
                else CustomEngine.parseExpressionAtEnd(textBeforeCursor).let { CustomEngine.expressionCalculator(textBeforeCursor, it).let(::showSymbols) }
            }
            chinesePrediction && InputModeSwitcher.isChinese-> {
                val textBeforeCursor = service.getTextBeforeCursor(10)
                if (textBeforeCursor.isBlank()) resetCandidateWindow()
                else {
                    DecodingInfo.getAssociateWord(textBeforeCursor)
                    updateCandidate()
                }
            }
            else -> resetCandidateWindow()
        }
    }
}
