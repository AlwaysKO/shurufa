package com.yuyan.imemodule.adapter

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.yuyan.imemodule.R
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.data.theme.ThemeManager.activeTheme
import com.yuyan.imemodule.keyboard.SogouKeyboardTypography
import com.yuyan.imemodule.singleton.EnvironmentSingleton
import com.yuyan.imemodule.utils.StringUtils.sbc2dbcCase
import com.yuyan.imemodule.view.popup.AutoScaleTextView

/**
 * 拼音选择
 */
class PrefixAdapter(
    context: Context?,
    private val mDatas: Array<String>,
    private val viewportHeight: Int? = null,
) :
    RecyclerView.Adapter<PrefixAdapter.SymbolTypeHolder>() {
    private val inflater: LayoutInflater = LayoutInflater.from(context)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SymbolTypeHolder {
        val view = inflater.inflate(R.layout.sdk_item_list_alpha_symbol_noraml, parent, false)
        return SymbolTypeHolder(view)
    }

    override fun onBindViewHolder(holder: SymbolTypeHolder, position: Int) {
        val text = sbc2dbcCase(mDatas[position]).orEmpty()
        val isPinyin = text.matches(Regex("[a-zü]+(?:'[a-zü]+)*"))
        val keyboardWidth = EnvironmentSingleton.instance.skbWidth.takeIf { it > 0 }
            ?: holder.itemView.resources.displayMetrics.widthPixels
        holder.tvSymbolType.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            SogouKeyboardTypography.mainTextSize(
                themeId = activeTheme.name,
                keyboardWidth = keyboardWidth,
                fontScale = ThemeManager.prefs.keyboardFontSize.getValue() / 100f,
                referenceSize = if (isPinyin) SogouKeyboardTypography.T9_SIDE_PINYIN_SIZE
                    else SogouKeyboardTypography.T9_SIDE_SYMBOL_SIZE,
                fallbackSize = holder.tvSymbolType.textSize,
            ),
        )
        val horizontalPadding = if (isPinyin) (keyboardWidth * 8 / SogouKeyboardTypography.REFERENCE_WIDTH).coerceAtLeast(1) else 0
        holder.tvSymbolType.setPadding(horizontalPadding, holder.tvSymbolType.paddingTop, horizontalPadding, holder.tvSymbolType.paddingBottom)
        holder.tvSymbolType.setText(text)
        viewportHeight?.takeIf { it >= 4 }?.let { height ->
            // APK CandidateCodeView 是四行；不能再让字号和 padding 决定行高。
            // 整数像素的余数分摊给四格，避免露出第五项。
            val row = position % 4
            holder.itemView.layoutParams.height = (row + 1) * height / 4 - row * height / 4
            holder.tvSymbolType.setPadding(horizontalPadding, 0, horizontalPadding, 0)
        }
    }

    override fun getItemCount(): Int {
        return mDatas.size
    }

    inner class SymbolTypeHolder(view: View) : RecyclerView.ViewHolder(view) {
        var tvSymbolType: AutoScaleTextView = view.findViewById(android.R.id.text1)
        init {
            tvSymbolType.scaleMode = AutoScaleTextView.Mode.Proportional
            tvSymbolType.setTextColor(SogouKeyboardTypography.MAIN_LABEL_COLOR)
            tvSymbolType.typeface = Typeface.DEFAULT
            tvSymbolType.includeFontPadding = false
        }
    }
}
