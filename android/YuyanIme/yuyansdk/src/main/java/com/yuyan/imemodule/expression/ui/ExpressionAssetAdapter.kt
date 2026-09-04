package com.yuyan.imemodule.expression.ui

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.yuyan.imemodule.R
import com.yuyan.imemodule.data.collect.ServerConfig
import com.yuyan.imemodule.expression.isLocalGifExpressionSource
import com.yuyan.imemodule.expression.model.ExpressionAsset
import com.yuyan.imemodule.expression.resolveExpressionRemoteSource

class ExpressionAssetAdapter(
    private val onLongPress: (ExpressionAsset) -> Unit = {},
    private val onClick: (ExpressionAsset) -> Unit,
) : RecyclerView.Adapter<ExpressionAssetAdapter.AssetHolder>() {
    private var items: List<ExpressionAsset> = emptyList()
    private var expanded = false
    private var layoutMetrics: ExpressionLayoutMetrics? = null

    fun setLayoutMetrics(metrics: ExpressionLayoutMetrics) {
        if (layoutMetrics == metrics) return
        layoutMetrics = metrics
        notifyDataSetChanged()
    }

    fun setExpanded(expanded: Boolean) {
        if (this.expanded == expanded) return
        this.expanded = expanded
        notifyDataSetChanged()
    }

    fun submitList(items: List<ExpressionAsset>) {
        this.items = items
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AssetHolder = AssetHolder(
        LayoutInflater.from(parent.context).inflate(
            R.layout.sdk_item_expression_asset,
            parent,
            false,
        ),
    )

    override fun onBindViewHolder(holder: AssetHolder, position: Int) {
        holder.bind(items[position], position)
    }

    override fun getItemCount(): Int = items.size

    override fun onViewRecycled(holder: AssetHolder) {
        Glide.with(holder.image).clear(holder.image)
    }

    inner class AssetHolder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.expression_asset_image)

        fun bind(asset: ExpressionAsset, position: Int) {
            itemView.visibility = View.VISIBLE
            val metrics = layoutMetrics
            itemView.layoutParams = itemView.layoutParams.apply {
                val size = when {
                    metrics == null -> itemSizePx(itemView)
                    expanded -> metrics.expandedItemSizePx
                    else -> metrics.itemSizePx
                }
                width = size
                height = size
                if (this is ViewGroup.MarginLayoutParams && metrics != null) {
                    marginEnd = if (expanded && (position + 1) % EXPANDED_COLUMNS == 0) {
                        0
                    } else {
                        metrics.itemGapPx
                    }
                }
            }
            val sources = previewSources(asset)
            val requestManager = Glide.with(image)
            val fallbackRequest = sources.fallback?.let { fallback ->
                requestManager
                    .load(fallback)
                    .centerCrop()
                    .listener(visibilityListener(hideOnFailure = true))
            }
            val primaryRequest = requestManager
                .load(sources.primary)
                .centerCrop()
                .listener(visibilityListener(hideOnFailure = fallbackRequest == null))
            if (fallbackRequest == null) {
                primaryRequest.into(image)
            } else {
                primaryRequest.error(fallbackRequest).into(image)
            }
            itemView.setOnClickListener { onClick(asset) }
            itemView.setOnLongClickListener {
                onLongPress(asset)
                true
            }
        }

        private fun visibilityListener(hideOnFailure: Boolean) = object : RequestListener<Drawable> {
            override fun onLoadFailed(
                error: GlideException?,
                model: Any?,
                target: Target<Drawable>,
                isFirstResource: Boolean,
            ): Boolean {
                if (hideOnFailure) {
                    itemView.visibility = View.GONE
                }
                return false
            }

            override fun onResourceReady(
                resource: Drawable,
                model: Any,
                target: Target<Drawable>?,
                dataSource: DataSource,
                isFirstResource: Boolean,
            ): Boolean {
                itemView.visibility = View.VISIBLE
                return false
            }
        }
    }

    private fun itemSizePx(view: View): Int =
        (ITEM_SIZE_DP * view.resources.displayMetrics.density).toInt()

    private companion object {
        const val ITEM_SIZE_DP = 104
        const val EXPANDED_COLUMNS = 3
    }
}

internal data class ExpressionPreviewSources(
    val primary: String,
    val fallback: String? = null,
)

internal fun previewSources(asset: ExpressionAsset): ExpressionPreviewSources {
    val primary = if (asset.format.equals("gif", ignoreCase = true)) {
        remoteCandidate(asset.resolvedPreviewUrl)?.takeIf { isLocalGifExpressionSource(it.path) }
            ?: remoteCandidate(asset.thumbnailUrl)?.takeIf { isLocalGifExpressionSource(it.path) }
            ?: remoteCandidate(asset.url)
            ?: assetCandidate(asset.fileName)
            ?: remoteCandidate(asset.thumbnailUrl)
            ?: thumbnailFileCandidate(asset)
    } else {
        remoteCandidate(asset.thumbnailUrl)
            ?: thumbnailFileCandidate(asset)
            ?: remoteCandidate(asset.url)
            ?: assetCandidate(asset.fileName)
    }
    val resolvedPrimary = requireNotNull(primary).resolve()
    val fallback = if (asset.format.equals("gif", ignoreCase = true)) {
        listOfNotNull(
            remoteCandidate(asset.thumbnailUrl),
            thumbnailFileCandidate(asset),
        ).map(PreviewCandidate::resolve).firstOrNull { it != resolvedPrimary }
    } else {
        null
    }
    return ExpressionPreviewSources(primary = resolvedPrimary, fallback = fallback)
}

internal fun previewSource(asset: ExpressionAsset): String = previewSources(asset).primary

private enum class PreviewPathKind { REMOTE, ASSET }

private data class PreviewCandidate(val path: String, val kind: PreviewPathKind) {
    fun resolve(): String = when (kind) {
        PreviewPathKind.ASSET -> "file:///android_asset/expression/${path.trimStart('/')}"
        PreviewPathKind.REMOTE -> resolveExpressionRemoteSource(ServerConfig.baseUrl, path)
    }
}

private fun remoteCandidate(path: String?): PreviewCandidate? =
    path?.takeIf { it.isNotBlank() }?.let { PreviewCandidate(it, PreviewPathKind.REMOTE) }

private fun assetCandidate(path: String?): PreviewCandidate? =
    path?.takeIf { it.isNotBlank() }?.let { PreviewCandidate(it, PreviewPathKind.ASSET) }

private fun thumbnailFileCandidate(asset: ExpressionAsset): PreviewCandidate? =
    if (asset.url != null) {
        remoteCandidate(asset.thumbnailFileName)
    } else {
        assetCandidate(asset.thumbnailFileName)
    }
