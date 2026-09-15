package com.yuyan.imemodule.keyboard.container

import android.annotation.SuppressLint
import android.content.Context
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.yuyan.imemodule.R
import com.yuyan.imemodule.data.sticker.StickerItem
import com.yuyan.imemodule.data.sticker.StickerSync
import com.yuyan.imemodule.data.collect.ServerConfig
import com.yuyan.imemodule.data.theme.ThemeManager.activeTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 斗图表情包面板：搜索框 + 表情包网格。
 * 点击表情包回调 onStickerClick（由 SymbolContainer 负责下载发送）。
 */
@SuppressLint("ViewConstructor")
class StickerPanel(context: Context, private val onStickerClick: (StickerItem) -> Unit) :
    LinearLayout(context) {

    private val scope = CoroutineScope(Dispatchers.Main)
    private val rv: RecyclerView
    private val searchInput: EditText
    private var adapter: StickerGridAdapter? = null

    init {
        inflate(context, R.layout.sdk_item_pager_sticker, this)
        rv = findViewById(R.id.sticker_grid_rv)
        searchInput = findViewById(R.id.sticker_search_input)
        rv.layoutManager = GridLayoutManager(context, 4)

        val searchBtn = findViewById<ImageView>(R.id.sticker_search_btn)
        searchBtn.drawable.setTint(activeTheme.keyTextColor)
        val doSearch = { search() }
        searchBtn.setOnClickListener { doSearch() }
        searchInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                doSearch()
                true
            } else false
        }
        load("") // 默认加载全部
    }

    /** 按关键词搜索并刷新网格（IO 线程请求，主线程更新） */
    private fun search() {
        val keyword = searchInput.text?.toString()?.trim() ?: ""
        load(keyword)
    }

    private fun load(keyword: String) {
        scope.launch {
            val stickers = withContext(Dispatchers.IO) { StickerSync.search(keyword) }
            if (adapter == null) {
                adapter = StickerGridAdapter(context, onStickerClick)
                rv.adapter = adapter
            }
            adapter?.submit(stickers)
        }
    }
}

/** 表情包网格适配器（Glide 加载，GIF 播放动画） */
private class StickerGridAdapter(
    private val context: Context,
    private val onStickerClick: (StickerItem) -> Unit,
) : RecyclerView.Adapter<StickerGridAdapter.Holder>() {

    private var stickers: List<StickerItem> = emptyList()

    fun submit(list: List<StickerItem>) {
        stickers = list
        notifyDataSetChanged()
    }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.sticker_item_img)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(context).inflate(R.layout.sdk_item_sticker, parent, false)
        return Holder(view)
    }

    override fun getItemCount(): Int = stickers.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val sticker = stickers[position]
        val fullUrl = ServerConfig.baseUrl + sticker.url
        Glide.with(context)
            .load(fullUrl)
            .into(holder.image)
        holder.itemView.setOnClickListener { onStickerClick(sticker) }
    }
}
