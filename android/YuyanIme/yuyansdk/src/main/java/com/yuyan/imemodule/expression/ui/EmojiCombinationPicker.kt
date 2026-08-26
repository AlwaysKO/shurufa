package com.yuyan.imemodule.expression.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.yuyan.imemodule.R
import com.yuyan.imemodule.data.collect.ServerConfig
import com.yuyan.imemodule.expression.EmojiSelectionState
import com.yuyan.imemodule.expression.EmojiSelectionStep
import com.yuyan.imemodule.expression.ExpressionCatalog
import com.yuyan.imemodule.expression.model.EmojiBase
import com.yuyan.imemodule.expression.model.EmojiCombination
import java.io.File

class EmojiCombinationPicker @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    private val state = EmojiSelectionState()
    private val title: TextView
    private val back: ImageButton
    private val list: RecyclerView
    private val preview: ImageView
    private val adapter = EmojiAdapter(::select)
    private var catalog: ExpressionCatalog? = null
    private var pendingDownloadKey: String? = null
    private var readyCombination: EmojiCombination? = null
    private var readyFile: File? = null

    var onCombinationMissing: ((EmojiCombination, (File?) -> Unit) -> Unit)? = null
    var onCombinationClick: ((EmojiCombination, File?) -> Unit)? = null

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.sdk_expression_emoji_picker, this, true)
        title = findViewById(R.id.expression_emoji_title)
        back = findViewById(R.id.expression_emoji_back)
        list = findViewById(R.id.expression_emoji_list)
        preview = findViewById(R.id.expression_emoji_preview)
        list.layoutManager = GridLayoutManager(context, 2, RecyclerView.HORIZONTAL, false)
        list.adapter = adapter
        preview.setOnClickListener {
            readyCombination?.let { onCombinationClick?.invoke(it, readyFile) }
        }
        back.setOnClickListener {
            state.backToFirst()
            render(requireNotNull(catalog))
        }
    }

    fun render(catalog: ExpressionCatalog) {
        this.catalog = catalog
        adapter.submitList(catalog.document.emojiBases.sortedBy(EmojiBase::sortOrder))
        back.visibility = if (state.step == EmojiSelectionStep.FIRST) View.INVISIBLE else View.VISIBLE
        title.text = when (state.step) {
            EmojiSelectionStep.FIRST -> "选择第一个表情"
            EmojiSelectionStep.SECOND -> "再选择一个表情"
            EmojiSelectionStep.PREVIEW -> "组合预览"
        }
        val showingPreview = state.step == EmojiSelectionStep.PREVIEW
        list.visibility = if (showingPreview) View.GONE else View.VISIBLE
        preview.visibility = if (showingPreview) View.VISIBLE else View.GONE
        if (showingPreview) showCombination(catalog)
    }

    fun reset() {
        state.reset()
        pendingDownloadKey = null
        clearReadyCombination()
        catalog?.let(::render)
    }

    private fun select(base: EmojiBase) {
        state.select(base.id)
        render(requireNotNull(catalog))
    }

    private fun showCombination(catalog: ExpressionCatalog) {
        val firstId = state.firstId ?: return
        val secondId = state.secondId ?: return
        val combination = catalog.findCombination(firstId, secondId)
        if (combination == null) {
            clearReadyCombination()
            showPlaceholder(catalog, secondId)
            return
        }
        if (assetExists(combination.fileName)) {
            readyCombination = combination
            readyFile = null
            load("file:///android_asset/expression/${combination.fileName}")
            return
        }
        clearReadyCombination()
        showPlaceholder(catalog, secondId)
        if (pendingDownloadKey == combination.key) return
        pendingDownloadKey = combination.key
        onCombinationMissing?.invoke(combination) { file ->
            post {
                if (pendingDownloadKey == combination.key) pendingDownloadKey = null
                if (state.combinationKey == combination.key && file?.isFile == true) {
                    readyCombination = combination
                    readyFile = file
                    load(file)
                }
            }
        }
    }

    private fun clearReadyCombination() {
        readyCombination = null
        readyFile = null
    }

    private fun showPlaceholder(catalog: ExpressionCatalog, id: String) {
        catalog.document.emojiBases.firstOrNull { it.id == id }?.let { base ->
            load(assetSource(base.fileName, base.url))
        }
    }

    private fun assetExists(fileName: String): Boolean = runCatching {
        context.assets.open("expression/$fileName").use { }
    }.isSuccess

    private fun load(source: Any) {
        Glide.with(preview).load(source).fitCenter().into(preview)
    }

    private fun assetSource(fileName: String, url: String?): String {
        val path = url ?: fileName
        return when {
            path.startsWith("http://") || path.startsWith("https://") -> path
            path.startsWith("/") -> ServerConfig.baseUrl + path
            else -> "file:///android_asset/expression/$path"
        }
    }

    private inner class EmojiAdapter(
        private val onClick: (EmojiBase) -> Unit,
    ) : RecyclerView.Adapter<EmojiAdapter.Holder>() {
        private var items: List<EmojiBase> = emptyList()

        fun submitList(items: List<EmojiBase>) {
            this.items = items
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
            LayoutInflater.from(parent.context).inflate(
                R.layout.sdk_item_expression_emoji,
                parent,
                false,
            ) as ImageView,
        )

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])
        override fun getItemCount(): Int = items.size

        inner class Holder(private val image: ImageView) : RecyclerView.ViewHolder(image) {
            fun bind(base: EmojiBase) {
                Glide.with(image).load(assetSource(base.fileName, base.url)).fitCenter().into(image)
                image.alpha = if (base.id == state.firstId) 0.65f else 1f
                image.setOnClickListener { onClick(base) }
            }
        }
    }
}
