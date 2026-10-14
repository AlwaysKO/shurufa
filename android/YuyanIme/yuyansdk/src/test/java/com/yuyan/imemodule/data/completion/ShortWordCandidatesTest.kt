package com.yuyan.imemodule.data.completion

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.GZIPInputStream

class ShortWordCandidatesTest {
    private val saved = mutableMapOf<String, Any?>()
    @Before fun setup() {
        val assets = File("src/main/assets/completion").takeIf { it.isDirectory }
            ?: File("yuyansdk/src/main/assets/completion")
        fun replace(name: String, value: Any?) {
            val field = OfflineT9Candidates::class.java.getDeclaredField(name).apply { isAccessible = true }
            saved[name] = field.get(OfflineT9Candidates)
            field.set(OfflineT9Candidates, value)
        }
        replace("lexicon", GZIPInputStream(assets.resolve("t9_lexicon.tsv.gzip").inputStream()).reader().use(T9Lexicon::parse))
        replace("domains", assets.resolve("chinese_domains.tsv").reader().use(T9Lexicon::parse))
        replace("publicPhrases", PublicPhraseIndex(ByteBuffer.wrap(assets.resolve("public_phrases.t9idx").readBytes())))
        replace("inputCompletions", InputCompletionIndex(ByteBuffer.wrap(assets.resolve("input_completion.t9idx").readBytes())))
        replace("store", null)
    }
    @After fun restore() {
        saved.forEach { (name, value) ->
            OfflineT9Candidates::class.java.getDeclaredField(name).apply { isAccessible = true }.set(OfflineT9Candidates, value)
        }
    }

    @Test fun `双字简拼可召回且原生单字和同字异读索引仍完整`() {
        val result = OfflineT9Candidates.select("64", listOf("你", "拟"), listOf("ni", "ni"))
        assertEquals(listOf("你", "拟"), result.firstPage.take(2).map { it.text })
        assertEquals(listOf(0, 1), result.firstPage.take(2).map { it.nativeIndex })
        // 64 也对应美国/能够等高频词；这里只要求可找，不伪造唯一正确首选。
        assertTrue(result.firstPage.map { it.text }.toString(), result.firstPage.any { it.text == "你好" })
        assertTrue(OfflineT9Candidates.select("25").firstPage.take(8).any { it.text == "北京" })
    }

    @Test fun `公开短词可用字母简拼与部分尾音而不要求全拼`() {
        for ((input, target) in listOf("bzd" to "不知道", "buzd" to "不知道",
            "2466434262" to "充电宝", "meiwent" to "没问题", "adou" to "爱豆")) {
            val words = OfflineT9Candidates.select(input).firstPage
            assertTrue(input + words.map { it.text }, words.take(16).any { it.text == target })
        }
    }

    @Test fun `已有全键简拼与混拼词不能因新增匹配证据被丢弃`() {
        for ((code, target) in listOf("bj" to "北京", "bugaox" to "不高兴")) {
            assertTrue(code, OfflineT9Candidates.select(code).firstPage.take(16).any { it.text == target })
        }
    }

    @Test fun `完整全键单音节原生首选不被短词补全挤掉`() {
        val result = OfflineT9Candidates.select("xia", listOf("下", "夏"), listOf("xia", "xia"))
        assertEquals(listOf("下", "夏"), result.firstPage.take(2).map { it.text })
        assertTrue(result.firstPage.any { it.text == "西安" })
    }

    @Test fun `精确英文候选保留首位但不匹配英文不能压住简拼`() {
        assertEquals("app", OfflineT9Candidates.select("app", listOf("app"), listOf("")).firstPage.first().text)
        assertNotEquals("other", OfflineT9Candidates.select("app", listOf("other"), listOf("")).firstPage.first().text)
    }

    @Test fun `两键同字异读和补词后的分页仍保留真实原生下标`() {
        val result = OfflineT9Candidates.select("48", listOf("和", "和"), listOf("hu", "huo"))
        assertEquals(listOf("hu", "huo"), result.firstPage.take(2).map { it.pinyin })
        assertEquals(listOf(0, 1), result.firstPage.take(2).map { it.nativeIndex })
        val size = result.firstPage.size
        assertEquals(listOf(0, 1), result.appendNativePage(listOf("和", "和"), "48", listOf("hu", "huo")))
        assertEquals(2, result.at(size)?.nativeIndex)
        assertEquals(3, result.at(size + 1)?.nativeIndex)
    }

    @Test fun `简拼索引不可用时后页可信短词仍可选且有相同匹配依据`() {
        val field = OfflineT9Candidates::class.java.getDeclaredField("inputCompletions").apply { isAccessible = true }
        field.set(OfflineT9Candidates, null)
        val result = OfflineT9Candidates.select("293", emptyList(), emptyList())
        val size = result.firstPage.size
        assertEquals(listOf(0), result.appendNativePage(listOf("不知道", "美国最高法院"), "293",
            listOf("bu zhi dao", "mei guo zui gao fa yuan")))
        assertEquals(0, result.at(size)?.nativeIndex)
        assertEquals(InputMatchKind.THREE_INITIALS, result.at(size)?.inputMatch?.kind)
    }

    @Test fun `新增短词召回不放开长词纯简拼或旧乱拼`() {
        assertFalse(OfflineT9Candidates.select("649439").firstPage.any { it.text == "美国最高法院" })
        assertFalse(OfflineT9Candidates.select("284269").firstPage.any { it.text == "长条形" })
        assertFalse(OfflineT9Candidates.select("nan's").firstPage.any { it.text == "男人" })
    }

    @Test fun `同层公共权重的微小差异不能把已有常用词挤出首屏`() {
        val words = listOf("这样", "这些", "这种", "这一", "协议", "谢谢")
        val readings = listOf("zhe yang", "zhe xie", "zhe zhong", "zhe yi", "xie yi", "xie xie")
        val result = OfflineT9Candidates.select("9439", words, readings).firstPage
        assertTrue(result.map { it.text }.toString(), result.take(5).any { it.text == "谢谢" })
        assertEquals(5, result.first { it.text == "谢谢" }.nativeIndex)
    }

    @Test fun `公共日常短词的尾部相邻错位可纠正且不学习错误码`() {
        val candidate = OfflineT9Candidates.select("meiwenit").firstPage.firstOrNull { it.text == "没问题" }
        assertNotNull(candidate)
        val value = requireNotNull(candidate)
        assertEquals(InputMatchKind.TYPO_CORRECTION, value.inputMatch?.kind)
        val tracker = T9CommitTracker()
        tracker.selected("meiwenit", value.text, value.pinyin, matchKind = value.inputMatch?.kind)
        assertNull(tracker.consumeSelection(value.text, true))
    }

    @Test fun `选择简拼后只学习实际码且成功上屏才交出选择`() {
        val candidate = OfflineT9Candidates.select("25").firstPage.firstOrNull { it.text == "北京" }
        assertNotNull(candidate)
        val value = requireNotNull(candidate)
        assertTrue(PersonalWordReading.matches("25", value.pinyin))
        val tracker = T9CommitTracker()
        tracker.selected("25", value.text, value.pinyin, matchKind = value.inputMatch?.kind)
        assertNull(tracker.consumeSelection(value.text, false))
        tracker.selected("25", value.text, value.pinyin, matchKind = value.inputMatch?.kind)
        assertEquals("25", tracker.consumeSelection(value.text, true)?.code)
    }
}
