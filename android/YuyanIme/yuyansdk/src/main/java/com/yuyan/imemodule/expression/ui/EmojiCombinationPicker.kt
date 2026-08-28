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
import kotlin.math.roundToInt

class EmojiCombinationPicker @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    private val state = EmojiSelectionState()
    private val title: TextView
    private val back: ImageButton
    private val header: LinearLayout
    private val body: ViewGroup
    private val list: RecyclerView
    private val preview: ImageView
    private val adapter = EmojiAdapter(::select)
    private var catalog: ExpressionCatalog? = null
    private var pendingDownloadKey: String? = null
    private var readyCombination: EmojiCombination? = null
    private var readyFile: File? = null
    private var singleLayerMode = false

    var onCombinationMissing: ((EmojiCombination, (File?) -> Unit) -> Unit)? = null
    var onCombinationClick: ((EmojiCombination, File?) -> Unit)? = null
    var onExitRequested: (() -> Unit)? = null

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.sdk_expression_emoji_picker, this, true)
        header = findViewById(R.id.expression_emoji_header)
        body = findViewById(R.id.expression_emoji_body)
        title = findViewById(R.id.expression_emoji_title)
        back = findViewById(R.id.expression_emoji_back)
        list = findViewById(R.id.expression_emoji_list)
        preview = findViewById(R.id.expression_emoji_preview)
        // 紧凑页使用单行横滑，保证每个表情仍有 44dp 触摸高度且不被内容区裁切。
        list.layoutManager = GridLayoutManager(context, 1, RecyclerView.HORIZONTAL, false)
        list.adapter = adapter
        preview.setOnClickListener {
            readyCombination?.let { onCombinationClick?.invoke(it, readyFile) }
        }
        back.setOnClickListener {
            if (state.step == EmojiSelectionStep.FIRST) {
                onExitRequested?.invoke()
            } else {
                state.backToFirst()
                render(requireNotNull(catalog))
            }
        }
    }

    fun render(catalog: ExpressionCatalog) {
        this.catalog = catalog
        adapter.submitList(catalog.document.emojiBases.sortedBy(EmojiBase::sortOrder))
        updateBackPresentation()
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

    /** 根据面板真实内容高度在双层与单层横滑之间切换，避免父容器裁掉44dp触摸目标。 */
    fun setAvailableHeight(heightPx: Int) {
        val minimum = (MINIMUM_TOUCH_TARGET_DP * resources.displayMetrics.density).roundToInt()
        val available = heightPx.coerceAtLeast(0)
        when {
            available >= minimum * 2 -> {
                singleLayerMode = false
                orientation = VERTICAL
                title.visibility = View.VISIBLE
                header.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, minimum)
                body.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, available - minimum)
            }

            available >= minimum -> {
                singleLayerMode = true
                orientation = HORIZONTAL
                title.visibility = View.VISIBLE
                header.layoutParams = LayoutParams(0, minimum, 1f)
                body.layoutParams = LayoutParams(0, minimum, 1f)
            }

            else -> {
                singleLayerMode = false
                orientation = VERTICAL
                title.visibility = View.GONE
                header.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0)
                body.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0)
            }
        }
        val previewSize = minOf(
            (PREVIEW_SIZE_DP * resources.displayMetrics.density).roundToInt(),
            (available - if (orientation == VERTICAL) minimum else 0).coerceAtLeast(minimum),
        )
        preview.layoutParams = preview.layoutParams.apply {
            width = previewSize
            height = previewSize
        }
        updateBackPresentation()
    }

    private fun updateBackPresentation() {
        val firstStep = state.step == EmojiSelectionStep.FIRST
        back.visibility = if (firstStep && !singleLayerMode) View.INVISIBLE else View.VISIBLE
        back.contentDescription = context.getString(
            if (firstStep) R.string.expression_emoji_exit_recommended
            else R.string.expression_emoji_back_first,
        )
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

    private companion object {
        const val MINIMUM_TOUCH_TARGET_DP = 44f
        const val PREVIEW_SIZE_DP = 52f
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
