package com.yuyan.imemodule.expression

import android.graphics.Bitmap
import android.graphics.Color
import com.yuyan.imemodule.expression.model.ExpressionAsset
import com.yuyan.imemodule.expression.model.ExpressionTextLayout
import com.yuyan.imemodule.expression.model.ExpressionTextSafeArea
import com.yuyan.imemodule.expression.ui.previewSource
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExpressionRecommendationResolverTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `预制图直接使用缩略图而合成模板生成带完整查询的本地预览`() = runBlocking {
        val source = temporaryFolder.newFile("source.png")
        Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(Color.BLUE)
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        var sourceCalls = 0
        val resolver = ExpressionRecommendationResolver(temporaryFolder.root) {
            sourceCalls += 1
            source
        }
        val prebuilt = asset("prebuilt", "prebuilt").copy(
            format = "gif",
            fileName = "prebuilt/prebuilt.gif",
            thumbnailFileName = "thumbnails/prebuilt.webp",
        )
        val templates = listOf(asset("one", "synthesis-template"), asset("two", "synthesis-template"))
        val query = "生僻完整词"

        val resolved = resolver.resolve(listOf(prebuilt, *templates.toTypedArray()), query)

        assertEquals(
            "file:///android_asset/expression/thumbnails/prebuilt.webp",
            previewSource(resolved.first()),
        )
        assertEquals(2, sourceCalls)
        val rendered = resolved.drop(1)
        assertTrue(rendered.all { it.thumbnailUrl?.startsWith("file://") == true })
        assertTrue(rendered.all { item ->
            val path = requireNotNull(item.thumbnailUrl).removePrefix("file://")
            File(path).isFile && File(path).name == "${resolver.cacheKey(item, query)}.webp"
        })
        assertNotEquals(resolver.cacheKey(templates.first(), query), resolver.cacheKey(templates.first(), "生僻"))
    }

    private fun asset(id: String, type: String) = ExpressionAsset(
        id = id,
        type = type,
        format = "webp",
        version = "v1",
        fileName = "templates/$id.webp",
        thumbnailFileName = "thumbnails/$id.webp",
        sha256 = id.padEnd(64, 'a').take(64),
        width = 128,
        height = 128,
        textSafeArea = ExpressionTextSafeArea(8, 8, 112, 48),
        layout = ExpressionTextLayout(
            minFontSize = 12,
            maxFontSize = 24,
            textColor = "#ffffff",
            strokeColor = "#000000",
            strokeWidth = 1,
            alignment = "center",
            maxLines = 2,
        ),
    )
}
