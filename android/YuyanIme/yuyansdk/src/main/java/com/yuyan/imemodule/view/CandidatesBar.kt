package com.yuyan.imemodule.view

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.OnScrollListener
import com.yuyan.imemodule.R
import com.yuyan.imemodule.adapter.CandidatesBarAdapter
import com.yuyan.imemodule.adapter.CandidatesMenuAdapter
import com.yuyan.imemodule.callback.CandidateViewListener
import com.yuyan.imemodule.application.CustomConstant
import com.yuyan.imemodule.data.flower.FlowerTypefaceMode
import com.yuyan.imemodule.data.keyboardToolbarVisualItems
import com.yuyan.imemodule.data.menuSkbFunsPreset
import com.yuyan.imemodule.data.mergeKeyboardToolbarVisualItems
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.database.DataBaseKT
import com.yuyan.imemodule.entity.SkbFunItem
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.prefs.behavior.SkbMenuMode
import com.yuyan.imemodule.service.DecodingInfo
import com.yuyan.imemodule.singleton.EnvironmentSingleton.Companion.instance
import com.yuyan.imemodule.keyboard.KeyboardManager
import com.yuyan.imemodule.keyboard.KeyboardToolbarMetrics
import com.yuyan.imemodule.keyboard.container.CandidatesContainer
import com.yuyan.imemodule.keyboard.container.ClipBoardContainer
import com.yuyan.imemodule.keyboard.container.InputBaseContainer
import com.yuyan.imemodule.manager.layout.CustomLinearLayoutManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import splitties.dimensions.dp

private const val CANDIDATE_FADE_TAG = "candidate_action_fade"

internal fun createCandidateOverlay(
    context: Context,
    candidates: RecyclerView,
    action: ImageView,
): FrameLayout {
    candidates.apply {
        if (layoutManager == null) {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        }
        isNestedScrollingEnabled = false
        setPaddingRelative(paddingStart, paddingTop,
            dp(CandidateOverlaySpec.touchTargetDp + CandidateOverlaySpec.overlapDp), paddingBottom)
        clipToPadding = false
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
    }
    action.apply {
        val leadingPadding = dp((CandidateOverlaySpec.touchTargetDp - CandidateOverlaySpec.iconDp) / 2)
        val trailingPadding = dp(CandidateOverlaySpec.touchTargetDp - CandidateOverlaySpec.iconDp) - leadingPadding
        setPadding(leadingPadding, leadingPadding, trailingPadding, trailingPadding)
        translationZ = dp(2).toFloat()
        layoutParams = FrameLayout.LayoutParams(
            dp(CandidateOverlaySpec.touchTargetDp),
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.END or Gravity.CENTER_VERTICAL,
        )
    }
    val fade = View(context).apply {
        tag = CANDIDATE_FADE_TAG
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        translationZ = action.translationZ
        layoutParams = FrameLayout.LayoutParams(
            dp(CandidateOverlaySpec.overlapDp), ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.END,
        ).apply { marginEnd = dp(CandidateOverlaySpec.touchTargetDp) }
    }
    return FrameLayout(context).apply {
        addView(candidates)
        addView(fade)
        addView(action)
        applyCandidateActionBackground(action, (action.background as? ColorDrawable)?.color ?: Color.WHITE)
    }
}

internal fun createCandidateActionBackground(color: Number): ColorDrawable =
    ColorDrawable(color.toInt() or 0xff000000.toInt())

internal fun applyCandidateActionBackground(view: View, color: Number) {
    view.background = createCandidateActionBackground(color)
    (view.parent as? ViewGroup)?.findViewWithTag<View>(CANDIDATE_FADE_TAG)?.background =
        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(color.toInt() and 0x00ffffff, color.toInt() or 0xff000000.toInt()))
}


internal fun toolbarPressBackground(): StateListDrawable = StateListDrawable().apply {
    addState(
        intArrayOf(android.R.attr.state_pressed),
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ThemeManager.activeTheme.keyPressHighlightColor)
        },
    )
    addState(
        intArrayOf(),
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
        },
    )
}

/**
 * 候选词集装箱
 */
class CandidatesBar(context: Context?, attrs: AttributeSet?) : RelativeLayout(context, attrs) {

    companion object {
        // 只调候选上方拼音；1.3 = 比原字号大 30%。
        const val COMPOSING_TEXT_SCALE = 1.5f
    }

    private lateinit var mCvListener: CandidateViewListener // 候选词视图监听器
    private lateinit var mRightArrowBtn: ImageView // 右边箭头按钮
    private lateinit var mMenuRightArrowBtn: ImageView
    private lateinit var mCandidatesDataContainer: LinearLayout //候选词视图
    private lateinit var mCandidatesMenuContainer: LinearLayout //控制菜单视图
    private lateinit var composingRow: LinearLayout
    private var expressionAction: TextView? = null
    private var composingHeight = 0
    private lateinit var mComposingView: TextView // 组成字符串的View，用于显示输入的拼音。
    private lateinit var mRVCandidates: RecyclerView    //候选词列表
    private lateinit var mIvMenuSetting: ImageView
    private lateinit var mLlContainer: LinearLayout
    private lateinit var mFlowerType: TextView
    private lateinit var mCandidatesAdapter: CandidatesBarAdapter
    private lateinit var mRVContainerMenu:RecyclerView   // 候选词栏菜单
    private lateinit var mCandidatesMenuAdapter: CandidatesMenuAdapter
    private lateinit var candidatesData: FrameLayout //候选词视图
    private var activeCandNo:Int = 0

    fun initialize(cvListener: CandidateViewListener) {
        mCvListener = cvListener
        initMenuView()
        initCandidateView()
    }

    // 初始化候选词界面
    private fun initCandidateView() {
        if(!::mCandidatesDataContainer.isInitialized) {
            mCandidatesDataContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                visibility = GONE
            }
            mComposingView = TextView(context).apply {
                includeFontPadding = false
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), 0, dp(10), 0)
            }
            mRightArrowBtn = ImageView(context).apply {
                isClickable = true
                isEnabled = true
                setImageResource(R.drawable.sdk_level_list_candidates_display)
                applyCandidateActionBackground(this, ThemeManager.activeTheme.barColor)
            }
            mRVCandidates = RecyclerView(context).apply {
                setItemAnimator(null)
                layoutManager =
                    CustomLinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            }
            mCandidatesAdapter = CandidatesBarAdapter(context)
            mCandidatesAdapter.setOnItemClickLitener { _: RecyclerView.Adapter<*>?, _: View?, position: Int ->
                mCvListener.onClickChoice(position)
            }
            mRVCandidates.setAdapter(mCandidatesAdapter)
            mRVCandidates.addOnScrollListener(object : OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                        DecodingInfo.activeCandidateBar =
                            layoutManager.findLastVisibleItemPosition()
                        val itemCount = recyclerView.adapter?.itemCount
                        if (KeyboardManager.instance.currentContainer !is CandidatesContainer && itemCount != null && DecodingInfo.activeCandidateBar >= itemCount - 1) {
                            DecodingInfo.nextPageCandidates
                        }
                    }
                }
            })
            composingRow = LinearLayout(context).apply {
                id = View.generateViewId()
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, dp(10), 0)
                visibility = GONE
            }
            composingRow.addView(mComposingView)
            addView(composingRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            this.addView(mCandidatesDataContainer)
            listOf(mCandidatesDataContainer, mCandidatesMenuContainer).forEach { container ->
                container.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    addRule(BELOW, composingRow.id)
                }
            }
            expressionAction?.let(::setExpressionAction)
        } else {
            (mRightArrowBtn.parent as? ViewGroup)?.removeView(mRightArrowBtn)
            (mRVCandidates.parent as? ViewGroup)?.removeView(mRVCandidates)
            if (::candidatesData.isInitialized) {
                mCandidatesDataContainer.removeView(candidatesData)
            }
        }
        val candidatesHeight = instance.effectiveCandidateRowHeight(resources.displayMetrics.density)
        candidatesData = createCandidateOverlay(context, mRVCandidates, mRightArrowBtn)
        candidatesData.layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, candidatesHeight)
        mCandidatesDataContainer.addView(candidatesData)
        mRightArrowBtn.setOnClickListener { view: View ->
            when (val level = (view as ImageView).drawable.level) {
                2 -> mCvListener.onClickClearCandidate()
                else -> {
                    mCvListener.onClickMore(level)
                    view.drawable.setLevel(1 - level)
                }
            }
        }
        mComposingView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, instance.composingTextSize * COMPOSING_TEXT_SCALE)
        // includeFontPadding=false，按真实 ascent/descent 补足拼音行，避免放大后上下裁切。
        val composingFont = mComposingView.paint.fontMetrics
        composingHeight = maxOf(
            instance.heightForcomposing,
            kotlin.math.ceil((composingFont.descent - composingFont.ascent).toDouble()).toInt(),
        )
        mComposingView.layoutParams = LinearLayout.LayoutParams(0, composingHeight, 1f)
        refreshComposingRow()
        mCandidatesAdapter.notifyChanged()
    }

    //初始化标题栏
    fun initMenuView() {
        if(!::mCandidatesMenuContainer.isInitialized) {
            this.removeAllViews()
            mCandidatesMenuContainer = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(ThemeManager.activeTheme.keyboardColor)
            }
            mIvMenuSetting = ImageView(context).apply {
                setImageResource(R.drawable.sdk_level_candidates_menu_left)
                isClickable = true
                isEnabled = true
                minimumWidth = dp(44)
                minimumHeight = dp(44)
                contentDescription = context.getString(R.string.skb_item_settings)
                background = toolbarPressBackground()
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    mCvListener.onClickMenu(SkbMenuMode.SettingsMenu)
                }
            }
            mLlContainer = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
            mFlowerType = TextView(context).apply {
                setTextColor(ThemeManager.activeTheme.keyTextColor)
                setPadding(dp(10), 0, 0, 0)
                minimumWidth = dp(44)
                minimumHeight = dp(44)
                gravity = Gravity.CENTER_VERTICAL
            }
            val flowerTypefaces = arrayOf(FlowerTypefaceMode.Mars, FlowerTypefaceMode.FlowerVine, FlowerTypefaceMode.Messy, FlowerTypefaceMode.Germinate,
                FlowerTypefaceMode.Fog,FlowerTypefaceMode.ProhibitAccess, FlowerTypefaceMode.Grass, FlowerTypefaceMode.Wind, FlowerTypefaceMode.Disabled)
            val flowerTypefacesName = resources.getStringArray(R.array.FlowerTypeface)
            if (CustomConstant.flowerTypeface == FlowerTypefaceMode.Disabled) {
                mLlContainer.visibility = GONE
            } else {
                mFlowerType.text =
                    flowerTypefacesName[flowerTypefaces.indexOf(CustomConstant.flowerTypeface)]
            }
            mFlowerType.setOnClickListener { _: View ->
                val popupMenu = PopupMenu(context, mLlContainer).apply {
                    menuInflater.inflate(R.menu.flower_typeface_menu, menu)
                    setOnMenuItemClickListener { menuItem ->
                        val ids = listOf(R.id.flower_type_mars, R.id.flower_type_flowervine, R.id.flower_type_messy, R.id.flower_type_grminate,
                            R.id.flower_type_fog, R.id.flower_type_prohibitaccess, R.id.flower_type_grass, R.id.flower_type_wind, R.id.flower_type_disabled
                        )
                        val position = ids.indexOf(menuItem.itemId)
                        val select = flowerTypefaces[position]
                        mFlowerType.text = flowerTypefacesName[position]
                        CustomConstant.flowerTypeface = select
                        if (select == FlowerTypefaceMode.Disabled) {
                            mLlContainer.visibility = GONE
                        }
                        mCandidatesMenuAdapter.notifyChanged()// 刷新菜单栏
                        false
                    }
                }
                popupMenu.show()
            }
            mLlContainer.addView(
                mFlowerType,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            mRVContainerMenu = RecyclerView(context).apply {
                setItemAnimator(null)
                layoutManager = CustomLinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            }
            mCandidatesMenuAdapter = CandidatesMenuAdapter(context)
            mCandidatesMenuAdapter.setOnItemClickLitener { _: RecyclerView.Adapter<*>?, view: View?, position: Int ->
                val skbMenuMode = mCandidatesMenuAdapter.getMenuMode(position)
                if (skbMenuMode != null) onClickMenu(skbMenuMode, view)
            }
            mRVContainerMenu.setAdapter(mCandidatesMenuAdapter)
            mMenuRightArrowBtn = ImageView(context).apply {
                isClickable = true
                isEnabled = true
                minimumWidth = dp(44)
                minimumHeight = dp(44)
                contentDescription = context.getString(R.string.keyboard_iv_menu_close)
                background = toolbarPressBackground()
                setImageResource(R.drawable.ic_menu_arrow_down)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            mMenuRightArrowBtn.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                mCvListener.onClickMenu(SkbMenuMode.CloseSKB)
            }
            mCandidatesMenuContainer.addView(mIvMenuSetting)
            mCandidatesMenuContainer.addView(mLlContainer)
            mCandidatesMenuContainer.addView(mRVContainerMenu)
            mCandidatesMenuContainer.addView(mMenuRightArrowBtn)
            this.addView(
                mCandidatesMenuContainer,
                LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            )
        }
        val menuHeight = instance.effectiveCandidateRowHeight(resources.displayMetrics.density)
        val slotWidth = KeyboardToolbarMetrics.slotWidth(instance.skbWidth)
        val functionIconSize = KeyboardToolbarMetrics.functionIconSize(instance.skbWidth, menuHeight)
        val functionHorizontalPadding = ((slotWidth - functionIconSize) / 2).coerceAtLeast(0)
        val functionVerticalPadding = ((menuHeight - functionIconSize) / 2).coerceAtLeast(0)
        mIvMenuSetting.scaleType = ImageView.ScaleType.FIT_CENTER
        mIvMenuSetting.setPadding(
            functionHorizontalPadding,
            functionVerticalPadding,
            functionHorizontalPadding,
            functionVerticalPadding,
        )
        val collapseIconSize = KeyboardToolbarMetrics.collapseIconSize(instance.skbWidth, menuHeight)
        mMenuRightArrowBtn.setPadding(
            ((slotWidth - collapseIconSize) / 2).coerceAtLeast(0),
            ((menuHeight - collapseIconSize) / 2).coerceAtLeast(0),
            ((slotWidth - collapseIconSize) / 2).coerceAtLeast(0),
            ((menuHeight - collapseIconSize) / 2).coerceAtLeast(0),
        )
        mFlowerType.textSize = instance.candidateTextSize
        mIvMenuSetting.layoutParams = LinearLayout.LayoutParams(slotWidth, menuHeight, 0f)
        mMenuRightArrowBtn.layoutParams = LinearLayout.LayoutParams(slotWidth, menuHeight, 0f)
        mLlContainer.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, menuHeight,0f)
        mRVContainerMenu.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, menuHeight, 1f)
        mCandidatesMenuAdapter.notifyChanged()  // 点击下拉菜单后，需要刷新菜单栏
    }

    private fun onClickMenu(skbMenuMode: SkbMenuMode, view: View?) {
        if(skbMenuMode == SkbMenuMode.ClearClipBoard){
            val contextWrapper = ContextThemeWrapper(context, R.style.Theme_AppTheme)
            val popupMenu = PopupMenu(contextWrapper, view).apply {
                menuInflater.inflate(R.menu.clear_clipboard, menu)
                setOnMenuItemClickListener { menuItem ->
                    when (menuItem.itemId) {
                        R.id.clear -> {
                            mCvListener.onClickClearClipBoard()
                        }
                    }
                    false
                }
            }
            popupMenu.show()
        } else {
            mCvListener.onClickMenu(skbMenuMode)
        }
    }

    // 增加窗口防抖机制
    private var pendingMenuJob: Job? = null
    private val serviceScope = MainScope()
    fun scheduleShowCandidates() {
        if (!DecodingInfo.isCandidatesEmpty) {
            pendingMenuJob?.cancel()
            pendingMenuJob = null
            showCandidates()
            return
        }
        if (pendingMenuJob?.isActive == true) return
        pendingMenuJob = serviceScope.launch {
            delay(100)
            showCandidates()
            pendingMenuJob = null
        }
    }

    /**
     * 显示候选词
     */
    fun showCandidates() {
        mComposingView.text = DecodingInfo.composingStrForDisplay
        refreshComposingRow()
        val container = KeyboardManager.instance.currentContainer
        mIvMenuSetting.drawable.setLevel( if(container is InputBaseContainer) 0 else 1)
        if (container is ClipBoardContainer) {
            showViewVisibility(mCandidatesMenuContainer)
            mCandidatesMenuAdapter.items = if(container.getMenuMode() == SkbMenuMode.ClipBoard) {
                keyboardToolbarVisualItems("clipboard", listOf(menuSkbFunsPreset[SkbMenuMode.ClearClipBoard]!!, menuSkbFunsPreset[SkbMenuMode.ClipBoard]!!, menuSkbFunsPreset[SkbMenuMode.Phrases]!!, menuSkbFunsPreset[SkbMenuMode.LockClipBoard]!!))
            } else {
                keyboardToolbarVisualItems("phrases", listOf(menuSkbFunsPreset[SkbMenuMode.AddPhrases]!!, menuSkbFunsPreset[SkbMenuMode.ClipBoard]!!, menuSkbFunsPreset[SkbMenuMode.Phrases]!!, menuSkbFunsPreset[SkbMenuMode.LockClipBoard]!!))
            }
        } else if (DecodingInfo.isCandidatesEmpty) {
            mRightArrowBtn.drawable.setLevel(0)
            showViewVisibility(mCandidatesMenuContainer)
            val mFunItems: MutableList<SkbFunItem> = mutableListOf()
            val barMenus = DataBaseKT.instance.skbFunDao().getALlBarMenu()
            for (item in barMenus) {
                val skbMenuMode = SkbMenuMode.decode(item.name)
                val skbFunItem = menuSkbFunsPreset[skbMenuMode]
                if (skbFunItem != null) {
                    mFunItems.add(skbFunItem)
                }
            }
            mCandidatesMenuAdapter.items = mergeKeyboardToolbarVisualItems(mFunItems)
        } else {
            if (DecodingInfo.candidateSize > DecodingInfo.activeCandidateBar) mRVCandidates.layoutManager?.scrollToPosition(DecodingInfo.activeCandidateBar)
            showViewVisibility(mCandidatesDataContainer)
            mRightArrowBtn.drawable.setLevel(if (DecodingInfo.isAssociate) 2 else if (KeyboardManager.instance.currentContainer is CandidatesContainer) 1 else 0)
        }
        activeCandNo = 0
        mCandidatesAdapter.activeCandidates(activeCandNo)
        mCandidatesAdapter.notifyChanged()
        mCandidatesMenuAdapter.notifyChanged()
    }

    /**
     * 显示表情
     */
    fun showEmoji() {
        showViewVisibility(mCandidatesMenuContainer)
        mCandidatesMenuAdapter.items = keyboardToolbarVisualItems(
            "emoji",
            listOf(menuSkbFunsPreset[SkbMenuMode.Emoticon]!!, menuSkbFunsPreset[SkbMenuMode.Emojicon]!!),
        )
        activeCandNo = 0
        mCandidatesAdapter.activeCandidates(activeCandNo)
        mCandidatesAdapter.notifyChanged()
        mCandidatesMenuAdapter.notifyChanged()
    }

    /**
     * 更新激活的候选词
     */
    fun updateActiveCandidateNo(keyCode: Int) {
        if (!DecodingInfo.isCandidatesEmpty) {
            when(keyCode){
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if(--activeCandNo <= 0) activeCandNo = 0
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if(++activeCandNo > DecodingInfo.candidateSize) activeCandNo = DecodingInfo.candidateSize
                }
            }
            mCandidatesAdapter.activeCandidates(activeCandNo)
            mCandidatesAdapter.notifyChanged()
            mRVCandidates.layoutManager?.scrollToPosition(if(activeCandNo - 1 > 0) activeCandNo - 1 else 0 )
        }
    }

    /**
     * 获取激活的候选词
     */
    fun getActiveCandNo():Int {
        return if(activeCandNo > 0) activeCandNo -1 else 0
    }

    /**
     * 是否操作选词
     */
    fun isActiveCand():Boolean {
        return activeCandNo > 0
    }

    /**
     * 选择花漾字
     */
    fun showFlowerTypeface() {
        if(CustomConstant.flowerTypeface == FlowerTypefaceMode.Disabled) {
            mLlContainer.visibility = GONE
        } else {
            CustomConstant.flowerTypeface = FlowerTypefaceMode.Mars
            mFlowerType.text = "焱暒妏"
            mLlContainer.visibility = VISIBLE
        }
    }

    /** 接管原面板按钮，复用其主题和回调；重复绑定不会添加第二个按钮。 */
    fun setExpressionAction(action: TextView) {
        expressionAction = action
        if (!::composingRow.isInitialized) return
        if (action.parent !== composingRow) {
            (action.parent as? ViewGroup)?.removeView(action)
            composingRow.addView(action, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(32)))
        }
        refreshComposingRow()
    }

    fun refreshComposingRow() {
        if (!::composingRow.isInitialized) return
        val hasAction = expressionAction?.visibility == VISIBLE
        val hasComposing = mComposingView.text.isNotBlank()
        composingRow.visibility = if (hasComposing || hasAction) VISIBLE else GONE
        composingRow.layoutParams = composingRow.layoutParams.apply {
            height = maxOf(if (hasComposing) composingHeight else 0, if (hasAction) dp(32) else 0)
        }
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val sharedHeight = if (::composingRow.isInitialized && composingRow.visibility == VISIBLE) {
            composingRow.layoutParams.height
        } else 0
        val heightMeasure = MeasureSpec.makeMeasureSpec(
            sharedHeight + instance.effectiveCandidateRowHeight(resources.displayMetrics.density),
            MeasureSpec.EXACTLY,
        )
        val widthMeasure = MeasureSpec.makeMeasureSpec(instance.skbWidth, MeasureSpec.EXACTLY)
        super.onMeasure(widthMeasure, heightMeasure)
    }

    private fun showViewVisibility(candidatesContainer: View) {
        if(candidatesContainer === mCandidatesMenuContainer){
            mCandidatesMenuContainer.visibility = VISIBLE
            mCandidatesDataContainer.visibility = GONE
        } else {
            mCandidatesMenuContainer.visibility = GONE
            mCandidatesDataContainer.visibility = VISIBLE
        }
    }

    // 刷新主题
    fun updateTheme(textColor: Int) {
        initMenuView()
        initCandidateView()
        mIvMenuSetting.background = toolbarPressBackground()
        mMenuRightArrowBtn.background = toolbarPressBackground()
        mCandidatesMenuContainer.setBackgroundColor(ThemeManager.activeTheme.keyboardColor)
        mIvMenuSetting.setImageResource(R.drawable.sdk_level_candidates_menu_left)
        mComposingView.setTextColor(textColor)
        applyCandidateActionBackground(mRightArrowBtn, ThemeManager.activeTheme.barColor)
        mRightArrowBtn.drawable.setTint(textColor)
        mMenuRightArrowBtn.drawable.setTint(textColor)
        mIvMenuSetting.drawable.setTint(textColor)
        mCandidatesAdapter.notifyChanged()
        mCandidatesMenuAdapter.notifyChanged()
        mFlowerType.setTextColor(textColor)
    }
}
