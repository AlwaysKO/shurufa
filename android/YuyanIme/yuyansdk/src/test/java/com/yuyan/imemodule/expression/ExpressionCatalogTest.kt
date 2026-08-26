package com.yuyan.imemodule.expression

import com.yuyan.imemodule.expression.model.EmojiCombination
import com.yuyan.imemodule.expression.model.ExpressionAsset
import com.yuyan.imemodule.expression.model.ExpressionCatalogDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ExpressionCatalogTest {
    @Test
    fun `只返回完整关键词精确匹配并标记为推荐`() {
        val document = document(
            assets = listOf(
                asset("hot", heat = 100),
                asset("emotion", emotions = listOf("放箭"), heat = 1),
                asset("exact-low", keywords = listOf("放箭"), heat = 1),
                asset("exact-high", keywords = listOf("放箭"), heat = 10),
            ),
        )
        val catalog = ExpressionCatalog(document)

        val results = catalog.search(" 放箭 ")

        assertEquals(listOf("exact-high", "exact-low"), results.map { it.id })
        assertEquals(listOf("recommendation", "recommendation"), results.map { it.type })
        assertEquals(
            listOf("hot", "emotion", "exact-low", "exact-high"),
            document.templates.map { it.id },
        )
        assertEquals(listOf("template", "template", "template", "template"), document.templates.map { it.type })
    }

    @Test
    fun `未知词不回退到热门模板`() {
        val catalog = ExpressionCatalog(document(assets = listOf(asset("hot", heat = 100))))

        assertEquals(emptyList<ExpressionAsset>(), catalog.search("未知词"))
    }

    @Test
    fun `远端增量按 ID 覆盖并保留本地素材`() {
        val local = ExpressionCatalog(document(assets = listOf(asset("local"), asset("shared", heat = 1))))
        val remote = document(
            version = "v2",
            assets = listOf(asset("shared", version = "v2", heat = 99), asset("remote")),
        )

        val merged = local.merge(remote)

        assertEquals("v2", merged.document.version)
        assertEquals(listOf("local", "shared", "remote"), merged.document.templates.map { it.id })
        assertEquals(99, merged.document.templates.first { it.id == "shared" }.heat)
    }

    @Test
    fun `Emoji 组合查找保留先后顺序`() {
        val catalog = ExpressionCatalog(
            document(
                combinations = listOf(
                    combination("angry", "cry"),
                    combination("cry", "angry"),
                ),
            ),
        )

        val forward = catalog.findCombination("angry", "cry")
        val reverse = catalog.findCombination("cry", "angry")
        assertEquals("angry__cry", forward?.key)
        assertEquals("cry__angry", reverse?.key)
        assertNotEquals(forward?.fileName, reverse?.fileName)
    }

    private fun asset(
        id: String,
        version: String = "v1",
        keywords: List<String> = emptyList(),
        emotions: List<String> = emptyList(),
        heat: Long = 0,
    ) = ExpressionAsset(
        id = id,
        type = "template",
        format = "webp",
        version = version,
        fileName = "templates/$id.webp",
        thumbnailFileName = "thumbnails/$id.webp",
        sha256 = id.padEnd(64, 'a').take(64),
        width = 512,
        height = 512,
        keywords = keywords,
        emotions = emotions,
        heat = heat,
    )

    private fun combination(first: String, second: String) = EmojiCombination(
        key = "${first}__${second}",
        firstId = first,
        secondId = second,
        fileName = "emoji-combinations/${first}__${second}.webp",
        sha256 = (first + second).padEnd(64, 'b').take(64),
        version = "v1",
        width = 256,
        height = 256,
        heat = 0,
    )

    private fun document(
        version: String = "v1",
        assets: List<ExpressionAsset> = emptyList(),
        combinations: List<EmojiCombination> = emptyList(),
    ) = ExpressionCatalogDocument(
        version = version,
        templates = assets,
        emojiBases = emptyList(),
        emojiCombinations = combinations,
    )
}
