package com.yuyan.imemodule.keyboard.container

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.yuyan.imemodule.R
import com.yuyan.imemodule.adapter.MenuAdapter
import com.yuyan.imemodule.application.CustomConstant
import com.yuyan.imemodule.data.menuSkbFunsPreset
import com.yuyan.imemodule.data.theme.Theme
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.data.theme.ThemeManager.activeTheme
import com.yuyan.imemodule.database.DataBaseKT
import com.yuyan.imemodule.database.entry.SkbFun
import com.yuyan.imemodule.entity.SkbFunItem
import com.yuyan.imemodule.manager.InputModeSwitcher
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.prefs.behavior.DoublePinyinSchemaMode
import com.yuyan.imemodule.prefs.behavior.SkbMenuMode
import com.yuyan.imemodule.singleton.EnvironmentSingleton
import com.yuyan.imemodule.keyboard.InputView
import com.yuyan.imemodule.keyboard.AndroidImeQuickKeyboardSettingsRuntime
import com.yuyan.imemodule.keyboard.ImeQuickKeyboardSettingsActions
import com.yuyan.imemodule.keyboard.QuickSymbolSurface
import com.yuyan.imemodule.keyboard.KeyboardManager
import com.yuyan.imemodule.keyboard.QuickKeyboardAction
import com.yuyan.imemodule.keyboard.QuickKeyboardLayoutId
import com.yuyan.imemodule.keyboard.QuickKeyboardSettingsActions
import com.yuyan.imemodule.keyboard.QuickKeyboardSettingsController
import com.yuyan.imemodule.keyboard.QuickKeyboardSettingsModel
import com.yuyan.imemodule.keyboard.QuickKeyboardPanelMetrics
import com.yuyan.imemodule.keyboard.KeyboardSurfaceThemes
import com.yuyan.imemodule.keyboard.SymbolPage
import com.yuyan.imemodule.keyboard.view.KeyboardThemePreviewView
import com.yuyan.imemodule.utils.KeyboardLoaderUtil
import com.yuyan.imemodule.manager.layout.CustomGridLayoutManager
import splitties.dimensions.dp
import java.util.Collections
import java.util.LinkedList

/**
 * 设置键盘容器
 *
 * 设置键盘、切换键盘界面容器。使用RecyclerView + GridLayoutManager。
 */
@SuppressLint("ViewConstructor")
class SettingsContainer(
    context: Context,
    inputView: InputView,
    quickSettingsActions: QuickKeyboardSettingsActions? = null,
) : BaseContainer(context, inputView) {
    /** 保留既有 AAR 的双参数 JVM 构造入口。 */
    constructor(context: Context, inputView: InputView) : this(context, inputView, null)

    private var mRVMenuLayout: RecyclerView? = null
    private var mTheme: Theme? = null
    private var adapter:MenuAdapter? = null
    private var selectedSymbolPage: SymbolPage? = null
    private var selectedQuickPage = QuickPanelPage.INPUT
    private val quickSettingsActions = quickSettingsActions ?: createQuickSettingsActions()
    private val quickSettingsController = QuickKeyboardSettingsController(this.quickSettingsActions)
    val funItems: MutableList<SkbFunItem> = LinkedList()   //键盘菜单对象

    val isQuickSettingsVisible: Boolean
        get() = quickSettingsController.isVisible

    companion object {
        internal fun applyQuickTheme(themeId: String): Boolean =
            AndroidImeQuickKeyboardSettingsRuntime.applyThemePreference(themeId)
    }

    private enum class QuickPanelPage { INPUT, THEME }

    init {
        initView(context)
    }

    private fun initView(context: Context) {
        mTheme = activeTheme
        mRVMenuLayout = RecyclerView(context)
        mRVMenuLayout!!.setHasFixedSize(true)
        mRVMenuLayout!!.setItemAnimator(null)
        val count = EnvironmentSingleton.instance.skbWidth/dp(100)
        val layoutManager = CustomGridLayoutManager(context, count)
        mRVMenuLayout!!.setLayoutManager(layoutManager)
        val layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        mRVMenuLayout!!.layoutParams = layoutParams
        this.addView(mRVMenuLayout)
    }

    private fun showMenuRecycler() {
        if (quickSettingsController.isVisible) {
            quickSettingsController.dismiss()
            inputView.onQuickKeyboardSettingsClosed()
        }
        removeAllViews()
        addView(mRVMenuLayout)
    }

    /**
     * 弹出键盘设置界面
     */
    fun showSettingsView() {
        showMenuRecycler()
        funItems.clear()
        for(item in DataBaseKT.instance.skbFunDao().getAllMenu()){
            val skbMenuMode = menuSkbFunsPreset[SkbMenuMode.decode(item.name)]
            if(skbMenuMode != null)funItems.add(skbMenuMode)
        }
        // 语音输入项兜底：老版本数据库无此菜单项时补插（新安装已含种子数据）
        if (funItems.none { it.skbMenuMode == SkbMenuMode.Voice }) {
            menuSkbFunsPreset[SkbMenuMode.Voice]?.let { voiceItem ->
                funItems.add(voiceItem)
                DataBaseKT.instance.skbFunDao().insertAll(listOf(SkbFun(name = SkbMenuMode.Voice.name, isKeep = 0, position = 100)))
            }
        }
        adapter = MenuAdapter(context, funItems)
        adapter?.setOnItemClickLitener { _: RecyclerView.Adapter<*>?, _: View?, position: Int ->
            inputView.onSettingsMenuClick(funItems[position].skbMenuMode)
        }
        mRVMenuLayout!!.setAdapter(adapter)
    }

    fun enableDragItem(enable: Boolean) {
        if (enable) {
            val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.Callback() {
                override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                    return makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.START or ItemTouchHelper.END, 0)
                }
                override fun onMove(recyclerView: RecyclerView, oldHolder: RecyclerView.ViewHolder, targetHolder: RecyclerView.ViewHolder): Boolean {
                    //使用集合工具类Collections，分别把中间所有的item的位置重新交换
                    val fromPosition: Int = oldHolder.bindingAdapterPosition //得到拖动ViewHolder的position
                    val toPosition: Int = targetHolder.bindingAdapterPosition //得到目标ViewHolder的position
                    if (fromPosition < toPosition) {
                        for (i in fromPosition until toPosition) {
                            Collections.swap(funItems, i, i + 1)
                        }
                    } else {
                        for (i in fromPosition downTo toPosition + 1) {
                            Collections.swap(funItems, i, i - 1)
                        }
                    }
                    adapter?.notifyItemMoved(fromPosition, toPosition)
                    funItems.forEachIndexed {index, item ->
                        DataBaseKT.instance.skbFunDao().update(SkbFun(name = item.skbMenuMode.name, isKeep = 0, position = index))
                    }
                    return true
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

                override fun canDropOver(recyclerView: RecyclerView, current: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = true

                override fun isLongPressDragEnabled() = false
            })

            adapter?.dragOverListener = object : MenuAdapter.DragOverListener {
                override fun startDragItem(holder: RecyclerView.ViewHolder) {
                    itemTouchHelper.startDrag(holder)
                }
                override fun onOptionClick(parent: RecyclerView.Adapter<*>?, v: SkbFunItem, position: Int) {
                    val barMenu = DataBaseKT.instance.skbFunDao().getBarMenu(v.skbMenuMode.name)
                    if(barMenu == null){
                        DataBaseKT.instance.skbFunDao().insert(
                            SkbFun(name = v.skbMenuMode.name, isKeep = 1, position = position),
                        )
                    } else {
                        DataBaseKT.instance.skbFunDao().delete(SkbFun(name = v.skbMenuMode.name, isKeep = 1))
                    }
                    inputView.updateCandidateBar()
                    adapter?.notifyDataSetChanged()
                }
            }
            itemTouchHelper.attachToRecyclerView(mRVMenuLayout)
        } else {
            adapter?.dragOverListener = null
        }
        adapter?.notifyDataSetChanged()
    }

    /**
     * 弹出键盘界面
     */
    fun showSkbSelelctModeView() {
        showMenuRecycler()
        val funItems: MutableList<SkbFunItem> = LinkedList()
        funItems.add(
            SkbFunItem(
                mContext.getString(R.string.keyboard_name_t9),
                R.drawable.selece_input_mode_py9,
                SkbMenuMode.PinyinT9
            )
        )
        funItems.add(
            SkbFunItem(
                mContext.getString(R.string.keyboard_name_cn26),
                R.drawable.selece_input_mode_py26,
                SkbMenuMode.Pinyin26Jian
            )
        )
        funItems.add(
            SkbFunItem(
                mContext.getString(R.string.keyboard_name_hand),
                R.drawable.selece_input_mode_handwriting,
                SkbMenuMode.PinyinHandWriting
            )
        )
        val doublePYSchemaMode = AppPrefs.getInstance().input.doublePYSchemaMode.getValue()
        val doublePinyinSchemaName = when (doublePYSchemaMode) {
            DoublePinyinSchemaMode.flypy -> R.string.double_pinyin_flypy_plus
            DoublePinyinSchemaMode.natural -> R.string.double_pinyin_natural
            DoublePinyinSchemaMode.abc -> R.string.double_pinyin_abc
            DoublePinyinSchemaMode.mspy -> R.string.double_pinyin_mspy
            DoublePinyinSchemaMode.sogou -> R.string.double_pinyin_sougou
            DoublePinyinSchemaMode.ziguang -> R.string.double_pinyin_ziguang
        }
        funItems.add(
            SkbFunItem(
                mContext.getString(doublePinyinSchemaName),
                R.drawable.selece_input_mode_dpy26,
                SkbMenuMode.Pinyin26Double
            )
        )
        funItems.add(
            SkbFunItem(
                mContext.getString(R.string.keyboard_name_pinyin_lx_17),
                R.drawable.selece_input_mode_lx17,
                SkbMenuMode.PinyinLx17
            )
        )
        funItems.add(
            SkbFunItem(
                mContext.getString(R.string.keyboard_name_stroke),
                R.drawable.selece_input_mode_stroke,
                SkbMenuMode.PinyinStroke
            )
        )
        val adapter = MenuAdapter(context, funItems)
        adapter.setOnItemClickLitener { _: RecyclerView.Adapter<*>?, _: View?, position: Int ->
            onKeyboardMenuClick(funItems[position])
        }
        mRVMenuLayout!!.setAdapter(adapter)
    }

    /** 在 IME 窗口内显示键盘和主题快捷面板。 */
    fun showQuickSettingsView(symbolPage: SymbolPage? = null) {
        selectedSymbolPage = symbolPage
        quickSettingsController.show()
        renderQuickSettingsView()
    }

    /** 再次点击同一入口时关闭；返回值表示切换后是否仍显示。 */
    fun toggleQuickSettingsView(): Boolean {
        val visible = quickSettingsController.toggle()
        if (visible) renderQuickSettingsView()
        return visible
    }

    /** 系统返回优先收起快捷面板。 */
    fun handleQuickSettingsBack(): Boolean = quickSettingsController.handleBack()

    /** 切到另一个工具前关闭快捷面板及其返回回调。 */
    fun closeQuickSettingsForTool(): Boolean = quickSettingsController.handleBack()

    private fun renderQuickSettingsView() {
        if (!quickSettingsController.isVisible) return
        removeAllViews()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xfff5f6fa.toInt())
        }
        root.addView(createQuickNavigation(), LinearLayout.LayoutParams(
            LayoutParams.MATCH_PARENT,
            dp(QuickKeyboardPanelMetrics.NAVIGATION_HEIGHT_DP),
        ))
        val content = when (selectedQuickPage) {
            QuickPanelPage.INPUT -> createInputMethodContent()
            QuickPanelPage.THEME -> createThemeContent()
        }
        root.addView(ScrollView(context).apply { addView(content) }, LinearLayout.LayoutParams(
            LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))
        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun createQuickNavigation() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), 0, dp(4), 0)
        addView(navigationIcon(R.drawable.ic_arrow_back_24, "quick_settings_back") {
            quickSettingsController.handleBack()
        }, LinearLayout.LayoutParams(dp(48), LayoutParams.MATCH_PARENT))
        addView(navigationTab(
            R.string.quick_settings_input_method,
            QuickPanelPage.INPUT,
            "quick_tab_input",
        ), LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(navigationTab(
            R.string.quick_settings_theme_layout,
            QuickPanelPage.THEME,
            "quick_tab_theme",
        ), LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(navigationIcon(R.drawable.ic_menu_setting, "quick_nav_more") {
            selectedQuickPage = QuickPanelPage.INPUT
            showSettingsView()
        }, LinearLayout.LayoutParams(dp(48), LayoutParams.MATCH_PARENT))
    }

    private fun navigationIcon(drawableRes: Int, tagValue: String, click: () -> Unit) = ImageView(context).apply {
        tag = tagValue
        contentDescription = context.getString(
            if (tagValue == "quick_settings_back") R.string.quick_settings_back_keyboard else R.string.quick_settings_more,
        )
        setImageResource(drawableRes)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        ImageViewCompat.setImageTintList(this, android.content.res.ColorStateList.valueOf(0xff1f252b.toInt()))
        setOnClickListener { click() }
    }

    private fun navigationTab(textRes: Int, page: QuickPanelPage, tagValue: String) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        val selected = selectedQuickPage == page
        addView(TextView(context).apply {
            text = context.getString(textRes)
            tag = tagValue
            isSelected = selected
            gravity = Gravity.CENTER
            setTextColor(0xff20242a.toInt())
            textSize = 16f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            minimumHeight = dp(QuickKeyboardPanelMetrics.MIN_TOUCH_TARGET_DP)
            setOnClickListener {
                if (selectedQuickPage != page) {
                    selectedQuickPage = page
                    renderQuickSettingsView()
                }
            }
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(View(context).apply {
            setBackgroundColor(if (selected) KeyboardSurfaceThemes.require(
                quickSettingsController.selectedThemeId.takeIf { KeyboardSurfaceThemes.fromThemeId(it) != null }
                    ?: "SogouDefault",
            ).accentColor else Color.TRANSPARENT)
        }, LinearLayout.LayoutParams(dp(32), dp(3)))
    }

    private fun createInputMethodContent() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(QuickKeyboardPanelMetrics.PANEL_SIDE_PADDING_DP), dp(8), dp(QuickKeyboardPanelMetrics.PANEL_SIDE_PADDING_DP), dp(12))
        val selectedLayout = QuickKeyboardSettingsModel.selectedLayout(
            InputModeSwitcher.skbLayout,
            InputModeSwitcher.isEnglish,
            selectedSymbolPage,
        )
        QuickKeyboardSettingsModel.layouts.chunked(QuickKeyboardPanelMetrics.INPUT_COLUMN_COUNT).forEach { rowItems ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                rowItems.forEach { option ->
                    addView(inputMethodButton(option.id, option.id == selectedLayout), LinearLayout.LayoutParams(
                        0,
                        dp(86),
                        1f,
                    ).apply { setMargins(dp(3), dp(5), dp(3), dp(5)) })
                }
                repeat(QuickKeyboardPanelMetrics.INPUT_COLUMN_COUNT - rowItems.size) {
                    addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
                }
            })
        }
    }

    private fun inputMethodButton(id: QuickKeyboardLayoutId, selected: Boolean) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        tag = "quick_layout_${id.name}"
        isSelected = selected
        minimumHeight = dp(QuickKeyboardPanelMetrics.MIN_TOUCH_TARGET_DP)
        background = panelCardBackground(selected, dp(10).toFloat())
        addView(ImageView(context).apply {
            setImageResource(layoutIcon(id))
            ImageViewCompat.setImageTintList(this, android.content.res.ColorStateList.valueOf(
                if (selected) currentSurfaceTheme().accentColor else 0xff303840.toInt(),
            ))
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }, LinearLayout.LayoutParams(dp(34), dp(34)))
        addView(TextView(context).apply {
            text = layoutLabel(id)
            gravity = Gravity.CENTER
            setTextColor(0xff20242a.toInt())
            textSize = 11f
            maxLines = 1
            typeface = Typeface.create(Typeface.DEFAULT, if (selected) Typeface.BOLD else Typeface.NORMAL)
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(30)))
        setOnClickListener { quickSettingsController.selectLayout(id) }
    }

    private fun createThemeContent() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val visible = quickSettingsController.themes.mapNotNull { KeyboardSurfaceThemes.fromThemeId(it.themeId) }
        val keyboardWidth = EnvironmentSingleton.instance.skbWidth
        val sidePadding = QuickKeyboardPanelMetrics.themeSidePadding(keyboardWidth)
        val gap = QuickKeyboardPanelMetrics.themeCardGap(keyboardWidth)
        val cardWidth = QuickKeyboardPanelMetrics.themeCardWidth(keyboardWidth)
        val cardHeight = QuickKeyboardPanelMetrics.themeCardHeight(keyboardWidth)
        setPadding(sidePadding, gap / 2, sidePadding, gap / 2)
        visible.chunked(QuickKeyboardPanelMetrics.THEME_COLUMN_COUNT).forEach { rowItems ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_HORIZONTAL
                rowItems.forEachIndexed { index, option ->
                    if (index > 0) addView(View(context), LinearLayout.LayoutParams(gap, 1))
                    addView(themeCard(option, cardHeight, keyboardWidth), LinearLayout.LayoutParams(cardWidth, cardHeight))
                }
            }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, cardHeight).apply {
                bottomMargin = gap
            })
        }
    }

    private fun themeCard(
        option: com.yuyan.imemodule.keyboard.KeyboardSurfaceTheme,
        cardHeight: Int,
        keyboardWidth: Int,
    ) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        tag = "quick_theme_${option.themeId}"
        val selected = option.themeId == quickSettingsController.selectedThemeId
        isSelected = selected
        minimumHeight = dp(QuickKeyboardPanelMetrics.MIN_TOUCH_TARGET_DP)
        val scale = keyboardWidth / QuickKeyboardPanelMetrics.REFERENCE_WIDTH.toFloat()
        setPadding((15 * scale).toInt(), (6 * scale).toInt(), (15 * scale).toInt(), (15 * scale).toInt())
        background = panelCardBackground(
            selected = selected,
            radius = QuickKeyboardPanelMetrics.themeCardRadius(keyboardWidth).toFloat(),
            selectedStrokeWidth = QuickKeyboardPanelMetrics.themeSelectedStroke(keyboardWidth),
        )
        addView(FrameLayout(context).apply {
            addView(TextView(context).apply {
                text = option.displayName
                setTextColor(0xff20242a.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_PX, QuickKeyboardPanelMetrics.themeTitleTextSize(keyboardWidth))
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                gravity = Gravity.CENTER_VERTICAL
            }, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                marginStart = dp(4)
                marginEnd = dp(26)
            })
            addView(TextView(context).apply {
                text = if (selected) "✓" else ""
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = 10f
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (selected) option.accentColor else 0xffe2e4e9.toInt())
                }
            }, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.END or Gravity.CENTER_VERTICAL).apply {
                marginEnd = dp(3)
            })
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, (96 * scale).toInt()))
        addView(KeyboardThemePreviewView(context).apply {
            bind(option, InputModeSwitcher.isQwert)
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        setOnClickListener {
            if (quickSettingsController.selectTheme(option.themeId)) renderQuickSettingsView()
        }
    }

    private fun panelCardBackground(
        selected: Boolean,
        radius: Float,
        selectedStrokeWidth: Int = dp(2),
    ) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(Color.WHITE)
        setStroke(
            if (selected) selectedStrokeWidth else dp(1),
            if (selected) currentSurfaceTheme().accentColor else 0xffedf0f4.toInt(),
        )
    }

    private fun currentSurfaceTheme() = KeyboardSurfaceThemes.fromThemeId(quickSettingsController.selectedThemeId)
        ?: KeyboardSurfaceThemes.require("SogouDefault")

    private fun layoutIcon(id: QuickKeyboardLayoutId): Int = when (id) {
        QuickKeyboardLayoutId.CHINESE_T9 -> R.drawable.selece_input_mode_py9
        QuickKeyboardLayoutId.CHINESE_QWERTY, QuickKeyboardLayoutId.ENGLISH_QWERTY -> R.drawable.selece_input_mode_py26
        QuickKeyboardLayoutId.HANDWRITING -> R.drawable.selece_input_mode_handwriting
        QuickKeyboardLayoutId.STROKE -> R.drawable.selece_input_mode_stroke
        QuickKeyboardLayoutId.LX17 -> R.drawable.selece_input_mode_lx17
        QuickKeyboardLayoutId.NUMBER -> R.drawable.ic_menu_shuzihang
        QuickKeyboardLayoutId.CHINESE_SYMBOL, QuickKeyboardLayoutId.ENGLISH_SYMBOL -> R.drawable.ic_menu_flower
        QuickKeyboardLayoutId.TEXT_EDIT -> R.drawable.ic_menu_cursor_icon
    }

    private fun layoutLabel(id: QuickKeyboardLayoutId): String = context.getString(
        when (id) {
            QuickKeyboardLayoutId.CHINESE_T9 -> R.string.keyboard_name_t9
            QuickKeyboardLayoutId.CHINESE_QWERTY -> R.string.quick_layout_chinese_qwerty
            QuickKeyboardLayoutId.ENGLISH_QWERTY -> R.string.quick_layout_english_qwerty
            QuickKeyboardLayoutId.HANDWRITING -> R.string.keyboard_name_hand
            QuickKeyboardLayoutId.STROKE -> R.string.keyboard_name_stroke
            QuickKeyboardLayoutId.NUMBER -> R.string.quick_layout_number
            QuickKeyboardLayoutId.CHINESE_SYMBOL -> R.string.quick_layout_chinese_symbol
            QuickKeyboardLayoutId.ENGLISH_SYMBOL -> R.string.quick_layout_english_symbol
            QuickKeyboardLayoutId.TEXT_EDIT -> R.string.quick_layout_text_edit
            QuickKeyboardLayoutId.LX17 -> R.string.keyboard_name_pinyin_lx_17
        },
    )

    private fun createQuickSettingsActions(): QuickKeyboardSettingsActions =
        ImeQuickKeyboardSettingsActions(
            AndroidImeQuickKeyboardSettingsRuntime(
                symbolSurface = QuickSymbolSurface { page ->
                    selectedSymbolPage = page
                    KeyboardManager.instance.switchKeyboard(KeyboardManager.KeyboardType.SYMBOL)
                    (KeyboardManager.instance.currentContainer as? SymbolContainer)?.setSymbolsView(
                        initialPage = if (page == SymbolPage.CHINESE) 1 else 2,
                    )
                },
                onInputSurfaceSelected = { selectedSymbolPage = null },
                onThemeChanged = { inputView.updateTheme() },
                onClose = { themeChanged ->
                    inputView.onQuickKeyboardSettingsClosed()
                    if (themeChanged) {
                        KeyboardLoaderUtil.instance.clearKeyboardMap()
                        KeyboardManager.instance.clearKeyboard()
                    }
                    KeyboardManager.instance.switchKeyboard()
                    inputView.updateCandidateBar()
                },
            ),
        )

    private fun onKeyboardMenuClick(data: SkbFunItem) {
        val value = when (data.skbMenuMode) {
            SkbMenuMode.Pinyin26Jian -> Pair(InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_PINYIN, CustomConstant.SCHEMA_ZH_QWERTY)
            SkbMenuMode.PinyinHandWriting -> Pair(InputModeSwitcher.MASK_SKB_LAYOUT_HANDWRITING, CustomConstant.SCHEMA_ZH_HANDWRITING)
            SkbMenuMode.PinyinLx17 -> Pair(InputModeSwitcher.MASK_SKB_LAYOUT_LX17, CustomConstant.SCHEMA_ZH_DOUBLE_LX17)
            SkbMenuMode.PinyinStroke -> Pair(InputModeSwitcher.MASK_SKB_LAYOUT_STROKE, CustomConstant.SCHEMA_ZH_STROKE)
            SkbMenuMode.Pinyin26Double -> Pair(InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_PINYIN, CustomConstant.SCHEMA_ZH_DOUBLE_FLYPY + AppPrefs.getInstance().input.doublePYSchemaMode.getValue())
            else -> Pair(InputModeSwitcher.MASK_SKB_LAYOUT_T9_PINYIN, CustomConstant.SCHEMA_ZH_T9)
        }
        InputModeSwitcher.switchModeForSetting(value)
        inputView.resetToIdleState()
    }
}
