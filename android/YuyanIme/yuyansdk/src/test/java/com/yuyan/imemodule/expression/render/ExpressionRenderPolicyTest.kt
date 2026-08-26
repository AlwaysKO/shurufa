package com.yuyan.imemodule.expression.render

import com.yuyan.imemodule.expression.model.ExpressionAsset
import com.yuyan.imemodule.expression.model.ExpressionTextLayout
import com.yuyan.imemodule.expression.model.ExpressionTextSafeArea
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpressionRenderPolicyTest {
    @Test
    fun `预制推荐始终直接使用原图`() {
        assertFalse(ExpressionRenderPolicy.shouldOverlayText(asset(type = "prebuilt"), "你好"))
    }

    @Test
    fun `模板仅在查询和文字布局完整时叠字`() {
        assertTrue(ExpressionRenderPolicy.shouldOverlayText(asset(type = "synthesis-template"), "你好"))
        assertFalse(ExpressionRenderPolicy.shouldOverlayText(asset(type = "synthesis-template"), "   "))
        assertFalse(
            ExpressionRenderPolicy.shouldOverlayText(
                asset(type = "synthesis-template").copy(textSafeArea = null),
                "你好",
            ),
        )
        assertFalse(
            ExpressionRenderPolicy.shouldOverlayText(
                asset(type = "synthesis-template").copy(layout = null),
                "你好",
            ),
        )
    }

    private fun asset(type: String) = ExpressionAsset(
        id = "asset",
        type = type,
        format = "webp",
        version = "v1",
        fileName = "templates/asset.webp",
        sha256 = "a".repeat(64),
        width = 512,
        height = 512,
        textSafeArea = ExpressionTextSafeArea(32, 32, 448, 128),
        layout = ExpressionTextLayout(
            minFontSize = 24,
            maxFontSize = 52,
            textColor = "#ffffff",
            strokeColor = "#000000",
            strokeWidth = 3,
            alignment = "center",
            maxLines = 2,
        ),
    )
}
