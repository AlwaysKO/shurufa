package com.yuyan.imemodule.expression.ui

import android.content.Context
import android.content.res.ColorStateList
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
import com.yuyan.imemodule.data.theme.Theme
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
    private var requestGeneration = 0
    private var readyCombination: EmojiCombination? = null
    private var readyFile: File? = null
    private var singleLayerMode = false
    private var renderedBases: List<EmojiBase>? = null
    private var bundledBaseHashes: Map<String, String> = emptyMap()

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
            if (preview.isEnabled) readyCombination?.let { combination ->
                readyFile?.takeIf { it.isFile }?.let { onCombinationClick?.invoke(combination, it) }
            }
        }
        title.setOnClickListener {
            if (title.isEnabled && state.step == EmojiSelectionStep.PREVIEW) catalog?.let(::showCombination)
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

    /** 来自 InputView 已解析的 APK 目录，不额外读盘，也不把远端 URL 当作必须联网。 */
    fun setBundledBases(bases: List<EmojiBase>) {
        bundledBaseHashes = bases.associate { it.fileName to it.sha256 }
    }

    fun render(catalog: ExpressionCatalog) {
        this.catalog = catalog
        if (renderedBases != catalog.document.emojiBases) {
            renderedBases = catalog.document.emojiBases
            adapter.submitList(catalog.document.emojiBases.sortedBy(EmojiBase::sortOrder))
        }
        adapter.updateSelection(state.firstId)
        updateBackPresentation()
        title.text = when (state.step) {
            EmojiSelectionStep.FIRST -> context.getString(R.string.expression_emoji_choose_first)
            EmojiSelectionStep.SECOND -> context.getString(R.string.expression_emoji_choose_second)
            EmojiSelectionStep.PREVIEW -> context.getString(R.string.expression_emoji_preview_title)
        }
        val showingPreview = state.step == EmojiSelectionStep.PREVIEW
        list.visibility = if (showingPreview) View.GONE else View.VISIBLE
        preview.visibility = if (showingPreview) View.VISIBLE else View.GONE
        if (showingPreview) {
            showCombination(catalog)
        } else {
            requestGeneration++
            pendingDownloadKey = null
            clearReadyCombination()
            title.isEnabled = false
        }
    }

    /** 面板与输入法主题同步，避免深色主题下标题或唯一退出入口不可见。 */
    fun updateTheme(theme: Theme) {
        setBackgroundColor(theme.keyboardColor)
        header.setBackgroundColor(theme.keyboardColor)
        body.setBackgroundColor(theme.keyboardColor)
        title.setTextColor(theme.keyTextColor)
        back.imageTintList = ColorStateList.valueOf(theme.keyTextColor)
    }

    fun reset() {
        state.reset()
        requestGeneration++
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
        val grid = available >= minimum * 3
        val columns = ((width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels) /
            (56 * resources.displayMetrics.density)).toInt().coerceIn(4, 8)
        val manager = list.layoutManager as GridLayoutManager
        val nextOrientation = if (grid) RecyclerView.VERTICAL else RecyclerView.HORIZONTAL
        val nextSpanCount = if (grid) columns else 1
        if (manager.orientation != nextOrientation || manager.spanCount != nextSpanCount) {
            manager.orientation = nextOrientation
            manager.spanCount = nextSpanCount
        }
        val previewSize = minOf(
            ((if (grid) 144f else PREVIEW_SIZE_DP) * resources.displayMetrics.density).roundToInt(),
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
            showFailure()
            return
        }
        if (readyCombination == combination && readyFile?.isFile == true) {
            title.setText(R.string.expression_emoji_preview_title)
            return
        }
        if (pendingDownloadKey == combination.key) {
            title.setText(R.string.expression_emoji_loading)
            return
        }
        clearReadyCombination()
        title.setText(R.string.expression_emoji_loading)
        title.isEnabled = false
        pendingDownloadKey = combination.key
        val generation = ++requestGeneration
        val resolve = onCombinationMissing
        if (resolve == null) {
            pendingDownloadKey = null
            showFailure()
            return
        }
        resolve(combination) { file ->
            post {
                if (generation != requestGeneration || state.step != EmojiSelectionStep.PREVIEW ||
                    state.combinationKey != combination.key) return@post
                pendingDownloadKey = null
                if (file?.isFile == true) {
                    readyCombination = combination
                    readyFile = file
                    preview.isEnabled = true
                    title.setText(R.string.expression_emoji_preview_title)
                    title.isEnabled = false
                    load(file)
                } else {
                    showFailure()
                }
            }
        }
    }

    private fun clearReadyCombination() {
        readyCombination = null
        readyFile = null
        preview.isEnabled = false
        Glide.with(preview).clear(preview)
        preview.setImageDrawable(null)
    }

    private fun showFailure() {
        clearReadyCombination()
        title.setText(R.string.expression_emoji_load_failed)
        title.isEnabled = true
    }

    private fun load(source: Any) {
        Glide.with(preview).load(source).fitCenter().into(preview)
    }

    private companion object {
        const val MINIMUM_TOUCH_TARGET_DP = 44f
        const val PREVIEW_SIZE_DP = 52f
    }

    private fun assetSource(base: EmojiBase): String {
        if (bundledBaseHashes[base.fileName] == base.sha256) {
            return "file:///android_asset/expression/${base.fileName}"
        }
        val path = base.url ?: base.fileName
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
        private var selectedId: String? = null

        fun submitList(items: List<EmojiBase>) {
            if (this.items == items) return
            this.items = items
            notifyDataSetChanged()
        }

        fun updateSelection(id: String?) {
            if (selectedId == id) return
            val previous = selectedId
            selectedId = id
            items.forEachIndexed { index, base ->
                if (base.id == previous || base.id == id) notifyItemChanged(index, "selection")
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
            LayoutInflater.from(parent.context).inflate(
                R.layout.sdk_item_expression_emoji,
                parent,
                false,
            ) as ImageView,
        )

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])
        override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
            if (payloads.isNotEmpty()) holder.updateSelection(items[position])
            else holder.bind(items[position])
        }
        override fun getItemCount(): Int = items.size

        override fun onViewRecycled(holder: Holder) { holder.clear() }

        inner class Holder(private val image: ImageView) : RecyclerView.ViewHolder(image) {
            private var boundSource: String? = null
            private var boundSha256: String? = null

            fun bind(base: EmojiBase) {
                val source = assetSource(base)
                if (source != boundSource || base.sha256 != boundSha256) {
                    boundSource = source
                    boundSha256 = base.sha256
                    Glide.with(image).load(source)
                        .signature(com.bumptech.glide.signature.ObjectKey(base.sha256))
                        .fitCenter().dontAnimate().into(image)
                }
                image.contentDescription = base.name
                updateSelection(base)
                image.setOnClickListener { onClick(base) }
            }

            fun updateSelection(base: EmojiBase) {
                image.alpha = if (base.id == selectedId) 0.65f else 1f
            }

            fun clear() {
                boundSource = null
                boundSha256 = null
                Glide.with(image).clear(image)
            }
        }
    }
}
