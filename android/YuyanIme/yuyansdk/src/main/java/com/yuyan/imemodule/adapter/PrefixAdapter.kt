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
class PrefixAdapter(context: Context?, private val mDatas: Array<String>) :
    RecyclerView.Adapter<PrefixAdapter.SymbolTypeHolder>() {
    private val inflater: LayoutInflater = LayoutInflater.from(context)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SymbolTypeHolder {
        val view = inflater.inflate(R.layout.sdk_item_list_alpha_symbol_noraml, parent, false)
        return SymbolTypeHolder(view)
    }

    override fun onBindViewHolder(holder: SymbolTypeHolder, position: Int) {
        holder.tvSymbolType.setText(sbc2dbcCase(mDatas[position]))
    }

    override fun getItemCount(): Int {
        return mDatas.size
    }

    inner class SymbolTypeHolder(view: View) : RecyclerView.ViewHolder(view) {
        var tvSymbolType: AutoScaleTextView = view.findViewById(android.R.id.text1)
        init {
            tvSymbolType.scaleMode = AutoScaleTextView.Mode.Proportional
            val keyboardWidth = EnvironmentSingleton.instance.skbWidth.takeIf { it > 0 }
                ?: view.resources.displayMetrics.widthPixels
            tvSymbolType.setTextColor(SogouKeyboardTypography.MAIN_LABEL_COLOR)
            tvSymbolType.setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                SogouKeyboardTypography.mainTextSize(
                    themeId = activeTheme.name,
                    keyboardWidth = keyboardWidth,
                    fontScale = ThemeManager.prefs.keyboardFontSize.getValue() / 100f,
                    referenceSize = SogouKeyboardTypography.T9_SIDE_SYMBOL_SIZE,
                    fallbackSize = tvSymbolType.textSize,
                ),
            )
            tvSymbolType.typeface = Typeface.DEFAULT
            tvSymbolType.includeFontPadding = false
        }
    }
}
