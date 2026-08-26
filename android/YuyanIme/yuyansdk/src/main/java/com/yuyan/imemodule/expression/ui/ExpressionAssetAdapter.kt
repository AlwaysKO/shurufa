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
import com.yuyan.imemodule.expression.model.ExpressionAsset

class ExpressionAssetAdapter(
    private val onLongPress: (ExpressionAsset) -> Unit = {},
    private val onClick: (ExpressionAsset) -> Unit,
) : RecyclerView.Adapter<ExpressionAssetAdapter.AssetHolder>() {
    private var items: List<ExpressionAsset> = emptyList()
    private var expanded = false

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
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    override fun onViewRecycled(holder: AssetHolder) {
        Glide.with(holder.image).clear(holder.image)
    }

    inner class AssetHolder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.expression_asset_image)

        fun bind(asset: ExpressionAsset) {
            itemView.visibility = View.VISIBLE
            itemView.layoutParams = itemView.layoutParams.apply {
                width = itemSizePx(itemView)
                height = itemSizePx(itemView)
            }
            Glide.with(image)
                .load(previewSource(asset))
                .centerCrop()
                .listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(
                        error: GlideException?,
                        model: Any?,
                        target: Target<Drawable>,
                        isFirstResource: Boolean,
                    ): Boolean {
                        itemView.visibility = View.GONE
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
                })
                .into(image)
            itemView.setOnClickListener { onClick(asset) }
            itemView.setOnLongClickListener {
                onLongPress(asset)
                true
            }
        }
    }

    private fun itemSizePx(view: View): Int =
        (ITEM_SIZE_DP * view.resources.displayMetrics.density).toInt()

    private companion object {
        const val ITEM_SIZE_DP = 104
    }
}

internal fun previewSource(asset: ExpressionAsset): String {
    val path = asset.thumbnailUrl ?: asset.thumbnailFileName ?: asset.url ?: asset.fileName
    return when {
        path.startsWith("http://") || path.startsWith("https://") || path.startsWith("file://") -> path
        path.startsWith("/") -> ServerConfig.baseUrl + path
        else -> "file:///android_asset/expression/$path"
    }
}
