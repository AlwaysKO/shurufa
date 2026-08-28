package com.yuyan.imemodule.keyboard.container

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
import com.yuyan.imemodule.keyboard.SymbolPage
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
    private var mRVMenuLayout: RecyclerView? = null
    private var mTheme: Theme? = null
    private var adapter:MenuAdapter? = null
    private var selectedSymbolPage: SymbolPage? = null
    private val quickSettingsActions = quickSettingsActions ?: createQuickSettingsActions()
    private val quickSettingsController = QuickKeyboardSettingsController(this.quickSettingsActions)
    val funItems: MutableList<SkbFunItem> = LinkedList()   //键盘菜单对象

    val isQuickSettingsVisible: Boolean
        get() = quickSettingsController.isVisible

    companion object {
        internal fun applyQuickTheme(themeId: String): Boolean =
            AndroidImeQuickKeyboardSettingsRuntime.applyThemePreference(themeId)
    }

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
                        DataBaseKT.instance.skbFunDao().insert(SkbFun(name = v.skbMenuMode.name, isKeep = 1))
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

    private fun renderQuickSettingsView() {
        if (!quickSettingsController.isVisible) return
        removeAllViews()
        val theme = ThemeManager.activeTheme
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(12))
        }
        content.addView(sectionTitle(context.getString(R.string.quick_settings_layout_title), theme.keyTextColor))

        val selectedLayout = QuickKeyboardSettingsModel.selectedLayout(
            InputModeSwitcher.skbLayout,
            InputModeSwitcher.isEnglish,
            selectedSymbolPage,
        )
        QuickKeyboardSettingsModel.layouts.chunked(2).forEach { rowItems ->
            content.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                rowItems.forEach { option ->
                    addView(
                        quickButton(
                            text = layoutLabel(option.id),
                            selected = option.id == selectedLayout,
                            tagValue = "quick_layout_${option.id.name}",
                        ) {
                            quickSettingsController.selectLayout(option.id)
                        },
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                            setMargins(dp(4), dp(4), dp(4), dp(4))
                        },
                    )
                }
                if (rowItems.size == 1) {
                    addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
                }
            })
        }

        content.addView(sectionTitle(context.getString(R.string.quick_settings_theme_title), theme.keyTextColor))
        content.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            quickSettingsController.themes.forEach { option ->
                addView(
                    quickButton(
                        text = if (option.isDark) {
                            context.getString(R.string.quick_theme_dark)
                        } else {
                            context.getString(R.string.quick_theme_light)
                        },
                        selected = option.themeId == quickSettingsController.selectedThemeId,
                        tagValue = "quick_theme_${option.themeId}",
                    ) {
                        if (quickSettingsController.selectTheme(option.themeId)) {
                            renderQuickSettingsView()
                        }
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(dp(4), dp(4), dp(4), dp(4))
                    },
                )
            }
        })
        content.addView(
            quickButton(
                text = context.getString(R.string.quick_settings_back_keyboard),
                selected = false,
                tagValue = "quick_settings_back",
            ) { quickSettingsController.handleBack() },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(4), dp(8), dp(4), 0)
            },
        )
        addView(
            ScrollView(context).apply { addView(content) },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }

    private fun sectionTitle(text: String, color: Int) = TextView(context).apply {
        this.text = text
        textSize = 15f
        setTextColor(color)
        setPadding(dp(6), dp(8), dp(6), dp(2))
    }

    private fun quickButton(
        text: String,
        selected: Boolean,
        tagValue: String,
        onClick: () -> Unit,
    ) = TextView(context).apply {
        this.text = if (selected) "✓ $text" else text
        tag = tagValue
        isSelected = selected
        gravity = Gravity.CENTER
        textSize = 14f
        minimumHeight = dp(48)
        setPadding(dp(8), dp(8), dp(8), dp(8))
        val theme = ThemeManager.activeTheme
        setTextColor(if (selected) theme.accentKeyTextColor else theme.keyTextColor)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(12).toFloat()
            setColor(if (selected) theme.accentKeyBackgroundColor else theme.keyBackgroundColor)
            setStroke(dp(if (selected) 2 else 1), if (selected) theme.accentKeyTextColor else Color.TRANSPARENT)
        }
        setOnClickListener { onClick() }
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