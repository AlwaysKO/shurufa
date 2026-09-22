package com.yuyan.imemodule.expression

import org.junit.Assert.*
import org.junit.Test

class ExpressionAutomaticRecommendationTest {
    private fun catalog(groups: String = """[{"keyword":"赞","aliases":["赞","点赞","给你点赞"],"assetIds":["owner","system"]}]""") = ExpressionCatalog.fromJson("""
        {"version":"v1","templates":[
          {"id":"system","type":"prebuilt","format":"gif","version":"v1","fileName":"a.gif","sha256":"a","width":1,"height":1,"keywords":["赞","点赞"],"embeddedText":"赞","heat":999},
          {"id":"owner","type":"prebuilt","format":"png","version":"v1","fileName":"b.png","sha256":"b","width":1,"height":1,"keywords":["赞"],"sourceType":"owner-upload"}
        ],"emojiBases":[],"emojiCombinations":[],"recommendationGroups":$groups}
    """)

    @Test fun `完整同组说法命中权威顺序而不按动态或热度重排`() {
        assertEquals(listOf("owner", "system"), catalog().recommend(" 给你点赞！ ").map { it.id })
        assertEquals(listOf("owner", "system"), catalog().search("给你点赞").map { it.id })
    }
    @Test fun `包含关键词的长句和内部标点空白不自动匹配`() {
        listOf("这是中华人民赞扬的美德", "今天给你点赞", "给你 点赞", "给你，点赞", "给你点赞❤").forEach {
            assertTrue(it, catalog().recommend(it).isEmpty())
        }
    }
    @Test fun `删除同组说法不由关键词或图中文字复活`() {
        val catalog = catalog("""[{"keyword":"赞","aliases":["给你点赞"],"assetIds":["owner","system"]}]""")
        assertTrue(catalog.recommend("赞").isEmpty())
        assertTrue(catalog.recommend("点赞").isEmpty())
    }
    @Test fun `空权威组不回退内置规则但手动查询仍宽松`() {
        val catalog = catalog("[]")
        assertTrue(catalog.recommend("赞").isEmpty())
        assertFalse(catalog.search("赞").isEmpty())
    }
    @Test fun `旧目录完整词别名回退默认个人在前`() {
        assertEquals(listOf("owner", "system"), catalog("null").recommend("给你点赞").map { it.id })
        assertTrue(catalog("null").recommend("这是中华人民赞扬的美德").isEmpty())
    }
    @Test fun `只忽略ECMAScript首尾空白及句末Unicode标点`() {
        assertEquals(listOf("owner", "system"), catalog().recommend("\ufeff给你点赞。　！\u00a0").map { it.id })
        assertTrue(catalog().recommend("给你\ufeff点赞").isEmpty())
    }
    @Test fun `完整快照写盘重读保留组别名删除和顺序`() {
        val dir = java.nio.file.Files.createTempDirectory("recommendation-groups").toFile()
        try {
            val store = ExpressionCatalogStore(dir, "https://example.com", "owner", "v1")
            store.write(catalog().document.copy(complete = true))
            val restored = ExpressionCatalog(requireNotNull(store.read()))
            assertEquals(listOf("owner", "system"), restored.recommend("给你点赞").map { it.id })
            store.write(catalog("[]").document.copy(complete = true))
            assertTrue(ExpressionCatalog(requireNotNull(store.read())).recommend("赞").isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun `现有太棒了称赞图支持给你点赞完整说法`() {
        val old = catalog("null").document
        val praise = ExpressionCatalog(old.copy(templates = old.templates.map {
            it.copy(keywords = listOf("太棒了"), embeddedText = "太棒了")
        }))
        assertEquals(listOf("owner", "system"), praise.recommend("给你点赞").map { it.id })
        assertTrue(praise.recommend("这是中华人民赞扬的美德").isEmpty())
    }

}
