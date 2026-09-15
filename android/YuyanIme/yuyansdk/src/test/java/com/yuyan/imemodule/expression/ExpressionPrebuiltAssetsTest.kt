package com.yuyan.imemodule.expression

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bumptech.glide.gifdecoder.GifHeaderParser
import com.yuyan.imemodule.expression.render.ExpressionRenderPolicy
import com.yuyan.imemodule.expression.send.ExpressionContentSender
import com.yuyan.imemodule.expression.ui.ExpressionPreviewSources
import com.yuyan.imemodule.expression.ui.previewSources
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@RunWith(RobolectricTestRunner::class)
class ExpressionPrebuiltAssetsTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `正式三词首屏均为四张不同风格的内置原创 GIF`() {
        val catalog = ExpressionCatalog.fromAssets(context)
        val source = Json.parseToJsonElement(File("../../../assets/expression/manifest.source.json").readText())
            .jsonObject.getValue("prebuiltAssets").jsonArray.map { it.jsonObject }
        for (phrase in listOf("谢谢", "无语", "笑死")) {
            val expected = source.filter { it.getValue("embeddedText").jsonPrimitive.content == phrase }
            assertEquals(4, expected.size)
            assertEquals(4, expected.map { it.getValue("style").jsonPrimitive.content }.toSet().size)
            val first = catalog.recommend(phrase, limit = 4)
            assertEquals(expected.map { it.getValue("id").jsonPrimitive.content }, first.map { it.id })
            assertTrue(first.all { it.type == "prebuilt" && it.format == "gif" && it.embeddedText == phrase })
        }
    }

    @Test
    fun `十二张预制 GIF 不解析源或编码预览并使用本地 GIF 和首帧回退`() = runBlocking {
        val resolver = ExpressionRecommendationResolver(temporaryFolder.root) {
            error("内置预制 GIF 不应进入源解析或手机编码")
        }
        val catalog = ExpressionCatalog.fromAssets(context)
        val originals = listOf("谢谢", "无语", "笑死").flatMap { catalog.recommend(it, limit = 4) }
        assertEquals(12, originals.size)
        val resolved = resolver.resolve(originals, "不应叠加的新查询")
        assertEquals(originals, resolved)
        assertTrue(temporaryFolder.root.listFiles().orEmpty().isEmpty())
        for (asset in resolved) {
            assertEquals(ExpressionPreviewSources(
                "file:///android_asset/expression/${asset.fileName}",
                "file:///android_asset/expression/${asset.thumbnailFileName}",
            ), previewSources(asset))
            val bytes = context.assets.open("expression/${asset.fileName}").use { it.readBytes() }
            assertTrue(GifHeaderParser().setData(bytes).parseHeader().numFrames >= 10)
            context.assets.open("expression/${asset.thumbnailFileName}").use { assertTrue(it.read() >= 0) }
        }
    }

    @Test
    fun `新五词完整目录各八张但只内置四张原GIF且全部保留首帧缩略图`() {
        val catalog = ExpressionCatalog.fromAssets(context)
        val source = Json.parseToJsonElement(File("../../../assets/expression/manifest.source.json").readText())
            .jsonObject.getValue("prebuiltAssets").jsonArray.map { it.jsonObject }
        for (phrase in listOf("你好", "早安", "晚安", "好的", "对不起")) {
            val expected = source.filter { it.getValue("embeddedText").jsonPrimitive.content == phrase }
            val recommended = catalog.recommend(phrase)
            assertEquals(8, expected.size)
            assertEquals(expected.map { it.getValue("id").jsonPrimitive.content }, recommended.map { it.id })
            assertEquals(4, recommended.count { it.distribution == "bundled" })
            assertEquals(4, recommended.count { it.distribution == "remote" })
            assertTrue(recommended.take(4).all { it.distribution == "bundled" })
            for (asset in recommended) {
                assertEquals("prebuilt", asset.type)
                assertEquals("gif", asset.format)
                assertFalse(ExpressionRenderPolicy.shouldOverlayText(asset, "不会手机叠字"))
                assertEquals("image/gif", ExpressionContentSender.mimeOf(asset.format))
                context.assets.open("expression/${asset.thumbnailFileName}").use { assertTrue(it.read() >= 0) }
                if (asset.distribution == "bundled") {
                    val bytes = context.assets.open("expression/${asset.fileName}").use { it.readBytes() }
                    assertEquals(16, GifHeaderParser().setData(bytes).parseHeader().numFrames)
                } else {
                    val attempt = runCatching { context.assets.open("expression/${asset.fileName}").use { it.readBytes() } }
                    assertTrue("remote GIF 不得内置: ${asset.id}", attempt.exceptionOrNull() is java.io.IOException)
                }
            }
        }
    }

    @Test
    fun `预制 GIF 发送策略不叠字且校验缓存与内置动画逐字节一致`() {
        val cache = ExpressionCache(temporaryFolder.root)
        val catalog = ExpressionCatalog.fromAssets(context)
        for (phrase in listOf("谢谢", "无语", "笑死")) {
            for (asset in catalog.recommend(phrase, limit = 4)) {
                assertFalse(ExpressionRenderPolicy.shouldOverlayText(asset, "新查询"))
                assertEquals("image/gif", ExpressionContentSender.mimeOf(asset.format))
                val bytes = context.assets.open("expression/${asset.fileName}").use { it.readBytes() }
                val cached = requireNotNull(cache.writeVerified(asset.version, asset.fileName, asset.sha256, bytes.inputStream()))
                assertArrayEquals(bytes, cached.readBytes())
            }
        }
    }
}
