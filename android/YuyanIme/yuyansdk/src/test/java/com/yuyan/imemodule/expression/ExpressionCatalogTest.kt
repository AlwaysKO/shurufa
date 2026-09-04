package com.yuyan.imemodule.expression

import com.yuyan.imemodule.expression.model.EmojiCombination
import com.yuyan.imemodule.expression.model.ExpressionAsset
import com.yuyan.imemodule.expression.model.ExpressionCatalogDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ExpressionCatalogTest {
    @Test
    fun `优先返回 embeddedText 完整精确匹配的预制图`() {
        val document = document(
            assets = listOf(
                asset("fallback", heat = 100),
                asset("unrelated", type = "prebuilt", embeddedText = "谢谢", heat = 100),
                asset("exact-low", type = "prebuilt", embeddedText = "放箭", heat = 1),
                asset("exact-high", type = "prebuilt", embeddedText = "放箭", heat = 10),
            ),
        )
        val catalog = ExpressionCatalog(document)

        val results = catalog.recommend(" 放箭 ")

        assertEquals(listOf("exact-high", "exact-low"), results.map { it.id })
        assertEquals(listOf("prebuilt", "prebuilt"), results.map { it.type })
        assertEquals(
            listOf("fallback", "unrelated", "exact-low", "exact-high"),
            document.templates.map { it.id },
        )
    }

    @Test
    fun `无预制结果时只返回限定数量的静态合成模板`() {
        val catalog = ExpressionCatalog(
            document(
                assets = listOf(
                    asset("cold", heat = 1),
                    asset("hot", heat = 10),
                    asset("unrelated", type = "prebuilt", embeddedText = "你好", heat = 100),
                ),
            ),
        )

        val results = catalog.recommend("今天的云像棉花糖", limit = 1)

        assertEquals(listOf("hot"), results.map { it.id })
        assertEquals(listOf("synthesis-template"), results.map { it.type })
    }

    @Test
    fun `普通词优先返回关键词语义相关模板而不是纯热度模板`() {
        val catalog = ExpressionCatalog(
            document(
                assets = listOf(
                    asset("hot-unrelated", keywords = listOf("开心庆祝"), heat = 999),
                    asset("glass-heart", keywords = listOf("伤心哭泣", "玻璃心"), heat = 1),
                    asset("heart", keywords = listOf("喜欢爱心"), heat = 2),
                ),
            ),
        )

        assertEquals(
            listOf("glass-heart", "heart", "hot-unrelated"),
            catalog.recommend("玻璃心").map { it.id },
        )
    }

    @Test
    fun `未知词兜底顺序按查询稳定变化`() {
        val catalog = ExpressionCatalog(
            document(assets = (0..7).map { asset("tpl-$it") }),
        )

        val first = catalog.recommend("甲词").map { it.id }
        assertEquals(first, catalog.recommend("甲词").map { it.id })
        assertNotEquals(first, catalog.recommend("乙词").map { it.id })
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
        type: String = "synthesis-template",
        embeddedText: String? = null,
        version: String = "v1",
        keywords: List<String> = emptyList(),
        emotions: List<String> = emptyList(),
        heat: Long = 0,
    ) = ExpressionAsset(
        id = id,
        type = type,
        format = "webp",
        version = version,
        fileName = "templates/$id.webp",
        thumbnailFileName = "thumbnails/$id.webp",
        sha256 = id.padEnd(64, 'a').take(64),
        width = 512,
        height = 512,
        keywords = keywords,
        emotions = emotions,
        embeddedText = embeddedText,
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
