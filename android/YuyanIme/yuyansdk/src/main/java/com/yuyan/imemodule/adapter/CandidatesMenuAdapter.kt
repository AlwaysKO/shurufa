package com.yuyan.imemodule.adapter

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.yuyan.imemodule.R
import com.yuyan.imemodule.application.CustomConstant
import com.yuyan.imemodule.callback.OnRecyclerItemClickListener
import com.yuyan.imemodule.data.flower.FlowerTypefaceMode
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.data.theme.ThemeManager.activeTheme
import com.yuyan.imemodule.entity.SkbFunItem
import com.yuyan.imemodule.keyboard.KeyboardManager
import com.yuyan.imemodule.keyboard.KeyboardToolbarModel
import com.yuyan.imemodule.keyboard.container.ClipBoardContainer
import com.yuyan.imemodule.keyboard.container.SymbolContainer
import com.yuyan.imemodule.manager.InputModeSwitcher
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.prefs.behavior.SkbMenuMode
import com.yuyan.imemodule.prefs.behavior.SymbolMode
import com.yuyan.imemodule.singleton.EnvironmentSingleton
import com.yuyan.imemodule.singleton.EnvironmentSingleton.Companion.instance
import splitties.dimensions.dp

/** 候选栏横向工具适配器；null 表示固定宽度、不可点击的视觉占位槽。 */
class CandidatesMenuAdapter(context: Context?) : RecyclerView.Adapter<CandidatesMenuAdapter.SymbolHolder>() {
    private val adapterContext: Context = requireNotNull(context)
    private val inflater: LayoutInflater = LayoutInflater.from(adapterContext)
    private var mOnItemClickListener: OnRecyclerItemClickListener? = null
    private var itemHeight: Int = maxOf((instance.heightForCandidatesArea * 0.8f).toInt(), adapterContext.dp(44))
    private var mMenuPadding: Int = (instance.heightForCandidatesArea * 0.05f).toInt()
    var items: List<SkbFunItem?> = emptyList()
        set(value) {
            val diffResult = DiffUtil.calculateDiff(MyDiffCallback(field, value))
            field = value
            diffResult.dispatchUpdatesTo(this)
        }

    fun setOnItemClickLitener(listener: OnRecyclerItemClickListener?) {
        mOnItemClickListener = listener
    }

    inner class SymbolHolder(view: View) : RecyclerView.ViewHolder(view) {
        val entranceIconImageView: ImageView = itemView.findViewById(R.id.candidates_menu_item)
    }

    override fun getItemViewType(position: Int): Int =
        if (items[position] == null) KeyboardToolbarModel.PLACEHOLDER_VIEW_TYPE else ACTION_VIEW_TYPE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SymbolHolder =
        SymbolHolder(inflater.inflate(R.layout.sdk_item_recyclerview_candidates_menu, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: SymbolHolder, position: Int) {
        val item = items[position]
        holder.itemView.minimumWidth = adapterContext.dp(44)
        holder.itemView.minimumHeight = adapterContext.dp(44)
        holder.itemView.layoutParams = holder.itemView.layoutParams.apply {
            width = itemHeight
            height = itemHeight
        }
        val icon = holder.entranceIconImageView
        icon.setPadding(mMenuPadding, mMenuPadding, mMenuPadding, mMenuPadding)
        icon.contentDescription = item?.funName
        if (item == null) {
            icon.visibility = View.INVISIBLE
            icon.setImageDrawable(null)
            holder.itemView.background = null
            holder.itemView.setOnClickListener(null)
            holder.itemView.isClickable = false
            holder.itemView.isFocusable = false
            return
        }

        icon.visibility = View.VISIBLE
        icon.setImageResource(item.funImgResource)
        val color = if (isSettingsMenuSelect(item)) activeTheme.accentKeyBackgroundColor else activeTheme.keyTextColor
        ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(color))
        holder.itemView.background = toolbarPressBackground()
        holder.itemView.isClickable = true
        holder.itemView.isFocusable = true
        holder.itemView.setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            val clickPosition = holder.bindingAdapterPosition
                .takeIf { it != RecyclerView.NO_POSITION } ?: position
            mOnItemClickListener?.onItemClick(this, view, clickPosition)
        }
    }

    fun getMenuMode(position: Int): SkbMenuMode? = items.getOrNull(position)?.skbMenuMode

    private fun toolbarPressBackground(): StateListDrawable = StateListDrawable().apply {
        addState(
            intArrayOf(android.R.attr.state_pressed),
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(activeTheme.keyPressHighlightColor)
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

    private fun isSettingsMenuSelect(data: SkbFunItem): Boolean {
        val rimeValue = AppPrefs.getInstance().internal.pinyinModeRime.getValue()
        return when (data.skbMenuMode) {
            SkbMenuMode.DarkTheme -> activeTheme.isDark
            SkbMenuMode.NumberRow -> AppPrefs.getInstance().keyboardSetting.abcNumberLine.getValue()
            SkbMenuMode.JianFan -> AppPrefs.getInstance().input.chineseFanTi.getValue()
            SkbMenuMode.LockEnglish -> AppPrefs.getInstance().keyboardSetting.keyboardLockEnglish.getValue()
            SkbMenuMode.SymbolShow -> ThemeManager.prefs.keyboardSymbol.getValue()
            SkbMenuMode.Mnemonic -> AppPrefs.getInstance().keyboardSetting.keyboardMnemonic.getValue()
            SkbMenuMode.EmojiInput -> AppPrefs.getInstance().input.emojiInput.getValue()
            SkbMenuMode.OneHanded -> AppPrefs.getInstance().keyboardSetting.oneHandedModSwitch.getValue()
            SkbMenuMode.FlowerTypeface -> CustomConstant.flowerTypeface != FlowerTypefaceMode.Disabled
            SkbMenuMode.FloatKeyboard -> EnvironmentSingleton.instance.keyboardModeFloat
            SkbMenuMode.ClipBoard -> (KeyboardManager.instance.currentContainer as? ClipBoardContainer)?.getMenuMode() == SkbMenuMode.ClipBoard
            SkbMenuMode.Phrases -> (KeyboardManager.instance.currentContainer as? ClipBoardContainer)?.getMenuMode() == SkbMenuMode.Phrases
            SkbMenuMode.Emojicon -> (KeyboardManager.instance.currentContainer as? SymbolContainer)?.getMenuMode() == SymbolMode.Emojicon
            SkbMenuMode.Emoticon -> (KeyboardManager.instance.currentContainer as? SymbolContainer)?.getMenuMode() == SymbolMode.Emoticon
            SkbMenuMode.PinyinT9 -> rimeValue == CustomConstant.SCHEMA_ZH_T9
            SkbMenuMode.Pinyin26Jian -> rimeValue == CustomConstant.SCHEMA_ZH_QWERTY
            SkbMenuMode.PinyinHandWriting -> rimeValue == CustomConstant.SCHEMA_ZH_HANDWRITING
            SkbMenuMode.PinyinLx17 -> rimeValue == CustomConstant.SCHEMA_ZH_DOUBLE_LX17
            SkbMenuMode.Pinyin26Double -> rimeValue.startsWith(CustomConstant.SCHEMA_ZH_DOUBLE_FLYPY) && rimeValue != CustomConstant.SCHEMA_ZH_DOUBLE_LX17
            SkbMenuMode.PinyinStroke -> rimeValue == CustomConstant.SCHEMA_ZH_STROKE
            SkbMenuMode.LockClipBoard -> CustomConstant.lockClipBoardEnable
            SkbMenuMode.TextEdit -> InputModeSwitcher.isTextEditSkb
            else -> false
        }
    }

    class MyDiffCallback(
        private val oldList: List<SkbFunItem?>,
        private val newList: List<SkbFunItem?>,
    ) : DiffUtil.Callback() {
        override fun getOldListSize(): Int = oldList.size
        override fun getNewListSize(): Int = newList.size
        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
            oldList[oldItemPosition]?.skbMenuMode == newList[newItemPosition]?.skbMenuMode
        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
            oldList[oldItemPosition]?.funName == newList[newItemPosition]?.funName
    }

    fun notifyChanged() {
        itemHeight = maxOf((instance.heightForCandidatesArea * 0.8f).toInt(), adapterContext.dp(44))
        mMenuPadding = (instance.heightForCandidatesArea * 0.05f).toInt()
        notifyDataSetChanged()
    }

    private companion object {
        const val ACTION_VIEW_TYPE = 0
    }
}
