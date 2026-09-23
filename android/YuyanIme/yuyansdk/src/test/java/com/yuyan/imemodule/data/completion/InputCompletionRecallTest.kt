package com.yuyan.imemodule.data.completion

import java.io.File
import java.nio.ByteBuffer
import java.util.zip.GZIPInputStream
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** JVM 直接使用正式词库和候选入口；不依赖 Android 原生运行库下载。 */
class InputCompletionRecallTest {
    @Before fun loadAssets() {
        val assets = File("src/main/assets/completion")
        fun set(name: String, value: Any?) {
            OfflineT9Candidates::class.java.getDeclaredField(name).apply { isAccessible = true }
                .set(OfflineT9Candidates, value)
        }
        set("lexicon", File(assets, "t9_lexicon.tsv.gzip").inputStream().use {
            GZIPInputStream(it).reader().use(T9Lexicon::parse)
        })
        set("domains", File(assets, "chinese_domains.tsv").reader().use(T9Lexicon::parse))
        set("publicPhrases", PublicPhraseIndex(ByteBuffer.wrap(File(assets, "public_phrases.t9idx").readBytes())))
        set("inputCompletions", InputCompletionIndex(ByteBuffer.wrap(File(assets, "input_completion.t9idx").readBytes())))
        set("store", null)
    }
    @Test fun `559从真实词库独立召回就可以`() {
        val result = OfflineT9Candidates.select("559", emptyList(), emptyList()).firstPage
        assertEquals("就可以", result.first().text)
        assertEquals("j'k'y", result.first().inputMatch?.preedit)
        assertTrue(result.any { it.text == "良口镇" })
    }
    @Test fun `拼音前缀两种键盘都召回整句`() {
        val full = "feiliuzhixiasanqianchi"
        for (length in "feiliuzhix".length..full.length) {
            val prefix = full.take(length)
            for (code in listOf(prefix, T9Lexicon.digits(prefix))) {
                assertTrue(code, OfflineT9Candidates.select(code, emptyList(), emptyList()).firstPage.take(8)
                    .any { it.text == "飞流直下三千尺" })
            }
        }
    }
    @Test fun `简拼原生索引与后页资格一致`() {
        val selection = OfflineT9Candidates.select("559", listOf("就可以"), listOf("jiu ke yi"))
        assertEquals(0, selection.firstPage.first { it.text == "就可以" }.nativeIndex)
        assertEquals(listOf(1), selection.appendNativePage(listOf("错误读音", "良口镇"), "559",
            listOf("cuo wu", "liang kou zhen")))
        assertEquals(2, selection.at(selection.firstPage.size)?.nativeIndex)
    }

    @Test fun `旧反例和短输入仍不触发过度补全`() {
        for ((code, text, reading) in listOf(
            Triple("649439", "美国最高法院", "mei guo zui gao fa yuan"),
            Triple("284269", "长条形", "chang tiao xing"),
        )) assertFalse(OfflineT9Candidates.select(code, listOf(text), listOf(reading)).firstPage.any { it.text == text })
        for (code in listOf("fei", "334", "feiliuzhixb", "33454894492")) {
            assertFalse(OfflineT9Candidates.select(code).firstPage.any { it.text == "飞流直下三千尺" })
        }
    }

    @Test fun `可信短词和诗句留出例同样能够召回`() {
        // 简拼与同码高频词、已有末字补全竞争；这里只约束召回，不挤掉已有可信整词。
        assertTrue(OfflineT9Candidates.select("257").firstPage.any { it.text == "不客气" })
        for ((code, text) in listOf("649" to "没关系", "976" to "为什么")) {
            val result = OfflineT9Candidates.select(code).firstPage
            assertTrue("$code $text ${result.take(25).map { it.text }} rank=${result.indexOfFirst { it.text == text }}", result.any { it.text == text })
        }
        for ((pinyin, text) in listOf("chuangqianmingy" to "床前明月光", "bairiyis" to "白日依山尽")) {
            for (code in listOf(pinyin, T9Lexicon.digits(pinyin))) {
                assertTrue("$code $text", OfflineT9Candidates.select(code).firstPage.take(8).any { it.text == text })
            }
        }
    }

    @Test fun `损坏索引拒绝加载`() {
        for (bytes in listOf(ByteArray(0), ByteArray(16), "T9COMP1\u0000".toByteArray() + ByteArray(8) { -1 })) {
            assertThrows(IllegalArgumentException::class.java) { InputCompletionIndex(ByteBuffer.wrap(bytes)) }
        }
    }
}
