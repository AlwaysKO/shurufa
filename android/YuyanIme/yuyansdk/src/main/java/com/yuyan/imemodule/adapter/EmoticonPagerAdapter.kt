package com.yuyan.imemodule.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.JustifyContent
import com.yuyan.imemodule.R
import com.yuyan.imemodule.database.DataBaseKT
import com.yuyan.imemodule.keyboard.container.StickerPanel
import com.yuyan.imemodule.manager.layout.CustomFlexboxLayoutManager
import com.yuyan.imemodule.data.sticker.StickerItem
import com.yuyan.imemodule.prefs.behavior.SymbolMode

/**
 * 颜文字 + 斗图混合分页适配器（SymbolMode.Emoticon 使用）：
 * 前 N 页为颜文字（复用 sdk_item_pager_symbols_emoji 布局），最后一页为斗图面板。
 */
class EmoticonPagerAdapter(
    context: Context,
    private val mDatas: Map<Int, List<String>>,
    private val onClickSymbol: (String, Int) -> Unit,
    private val onStickerClick: (StickerItem) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val mContext: Context

    /** 页面类型：0 = 颜文字页，1 = 斗图页 */
    companion object {
        const val TYPE_SYMBOL = 0
        const val TYPE_STICKER = 1
    }

    init {
        mContext = context
    }

    /** 斗图页永远在最后一页 */
    val stickerPagePosition: Int
        get() = mDatas.size

    inner class SymbolHolder(view: View) : RecyclerView.ViewHolder(view) {
        val emojiGroupRv: RecyclerView = view.findViewById(R.id.emojiGroupRv)
    }

    inner class StickerHolder(view: View) : RecyclerView.ViewHolder(view) {
        val panel: StickerPanel = view as StickerPanel
    }

    override fun getItemViewType(position: Int): Int =
        if (position >= mDatas.size) TYPE_STICKER else TYPE_SYMBOL

    override fun getItemCount(): Int = mDatas.size + 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_STICKER) {
            StickerHolder(StickerPanel(mContext, onStickerClick))
        } else {
            SymbolHolder(LayoutInflater.from(mContext).inflate(R.layout.sdk_item_pager_symbols_emoji, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is SymbolHolder) {
            val key = mDatas.keys.toList()[position]
            val item = when (key) {
                R.drawable.icon_emojibar_recents ->
                    DataBaseKT.instance.usedSymbolDao().getAllSymbolEmoji().map { it.symbol }.takeIf { it.isNotEmpty() }
                        ?: mDatas[mDatas.keys.toList()[1]]
                else -> mDatas[key]
            }
            val manager = CustomFlexboxLayoutManager(mContext)
            manager.flexDirection = FlexDirection.ROW
            manager.flexWrap = FlexWrap.WRAP
            manager.justifyContent = JustifyContent.SPACE_AROUND
            holder.emojiGroupRv.layoutManager = manager
            val mSymbolAdapter = SymbolAdapter(mContext, SymbolMode.Emoticon, position, onClickSymbol)
            mSymbolAdapter.mDatas = item
            holder.emojiGroupRv.adapter = mSymbolAdapter
        }
    }
}
