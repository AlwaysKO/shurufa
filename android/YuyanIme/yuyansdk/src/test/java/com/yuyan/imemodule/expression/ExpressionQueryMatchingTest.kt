package com.yuyan.imemodule.expression

import com.yuyan.imemodule.expression.model.ExpressionAsset
import com.yuyan.imemodule.expression.model.ExpressionCatalogDocument
import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ExpressionQueryMatchingTest {
    @Test fun `句子内原动作短语优先近义且长短语优先短短语`() {
        val query = "你过来打我啊"
        val exact = ExpressionQueryMatching.score(query, listOf("打我"))
        val related = ExpressionQueryMatching.score(query, listOf("揍你"))
        val longer = ExpressionQueryMatching.score(query, listOf("过来打我"))
        assertTrue(exact > related)
        assertTrue(longer > exact)
        assertTrue(ExpressionQueryMatching.score(query, listOf(query)) > longer)
    }

    @Test fun `Unicode 空白归一化与服务端一致`() {
        for (query in listOf("谢　谢啦", "谢\u00a0谢啦", "谢\ufeff谢啦")) {
            assertEquals(query, "谢谢啦", ExpressionQueryMatching.normalize(query))
        }
    }

    @Test fun `共享矩阵覆盖别名长句否定并保留固定文字`() {
        val words = listOf("谢谢", "打闹", "追赶", "开心", "难过", "可以", "不要", "喜欢")
        val assets = words.map { word -> ExpressionAsset(
            id = word, type = "prebuilt", format = "gif", embeddedText = word,
            keywords = when(word) { "打闹" -> listOf("打闹", "打你", "玩闹"); "追赶" -> listOf("追赶", "抓你", "追你"); else -> listOf(word) },
            version = "1", fileName = "$word.gif", sha256 = "a".repeat(64), width = 240, height = 240,
        ) }
        val catalog = ExpressionCatalog(ExpressionCatalogDocument(version = "1", templates = assets, emojiBases = emptyList(), emojiCombinations = emptyList()))
        val cases = Json.parseToJsonElement(File("../../../assets/expression/query/recommendation-cases.json").readText()).jsonArray
        for (case in cases) {
            val row = case.jsonObject
            val query = row.getValue("query").jsonPrimitive.content
            val results = catalog.search(query)
            row.getValue("first").jsonPrimitive.contentOrNull?.let { assertEquals(query, it, results.firstOrNull()?.id) }
            for (blocked in row.getValue("forbidden").jsonArray) assertFalse(query, results.any { it.id == blocked.jsonPrimitive.content })
            results.forEach { assertEquals(query, it.id, it.embeddedText) }
            val templates = ExpressionCatalog(catalog.document.copy(templates = assets.map { it.copy(type = "synthesis-template", embeddedText = null) }))
            val fallback = templates.search(query)
            if (query.startsWith("不") || query.startsWith("别")) for (blocked in row.getValue("forbidden").jsonArray) assertFalse("template: $query", fallback.any { it.id == blocked.jsonPrimitive.content })
        }
    }
}
