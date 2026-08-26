package com.yuyan.imemodule.expression.render

import com.yuyan.imemodule.expression.model.ExpressionAsset

object ExpressionRenderPolicy {
    fun shouldOverlayText(asset: ExpressionAsset, query: String): Boolean =
        asset.type == "synthesis-template" &&
            query.isNotBlank() &&
            asset.textSafeArea != null &&
            asset.layout != null
}
