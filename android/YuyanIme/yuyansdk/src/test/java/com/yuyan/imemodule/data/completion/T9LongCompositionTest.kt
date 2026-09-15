package com.yuyan.imemodule.data.completion

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.data.collect.LocalInputStore
import com.yuyan.inputmethod.util.T9Spelling
import java.util.zip.GZIPInputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 手机已安装 .15 APK 的原生JNI数组；不是编造候选或手机UI验收。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class T9LongCompositionTest {
    @Test fun `长句继续输入和退格时已有可信前缀不能落到大量单字后面`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val field = OfflineT9Candidates::class.java.getDeclaredField("store").apply { isAccessible = true }
        fun closeStore() {
            (field.get(OfflineT9Candidates) as? LocalInputStore)?.close()
            field.set(OfflineT9Candidates, null)
        }
        closeStore()
        context.deleteDatabase("local_input.db")
        OfflineT9Candidates.init(context)
        try {
            val lines = GZIPInputStream(javaClass.getResourceAsStream("/t9-long-native-replay.tsv.gz")!!)
                .bufferedReader().use { it.readLines() }
            val snapshots = mutableListOf<Triple<String, List<String>, List<String>>>()
            var cursor = 0
            while (cursor < lines.size) {
                val header = lines[cursor++].split('\t')
                assertEquals("CASE", header[0])
                val texts = mutableListOf<String>()
                val readings = mutableListOf<String>()
                repeat(header[2].toInt()) {
                    val row = lines[cursor++].split('\t')
                    assertEquals("CAND", row[0])
                    texts.add(row[1]); readings.add(row[2])
                }
                snapshots.add(Triple(header[1], texts, readings))
            }
            val full = T9Lexicon.digits("wobuzaipanghuang")
            assertEquals("9628924726448264", full)
            assertEquals((1..full.length).map(full::take), snapshots.map { it.first })
            // 正向及逆向快照回放，不冒充原生退格交互。
            for ((code, texts, readings) in snapshots + snapshots.asReversed()) {
                val selection = OfflineT9Candidates.select(code, texts, readings)
                assertTrue("候选非空: $code", selection.firstPage.isNotEmpty())
                if (code.length >= T9Lexicon.digits("wobuzai").length) {
                    val index = selection.firstPage.indexOfFirst { it.text == "我不再" }
                    assertTrue("$code 的我不再被挤到 $index，应该在前8项", index in 0..7)
                    assertEquals(texts.indexOf("我不再"), selection.at(index)?.nativeIndex)
                    assertEquals("wo'bu'zai", selection.at(index)?.pinyin)
                }
                // 新要求保留原生完整解码；逐键中间态可能有暂时不自然的整句，记录而不伪称语义正确。
                println("LONG_SENTENCE_REPLAY\t$code\t${selection.firstPage.joinToString("|") { it.text }}")
                if (code in T9Spelling.completionCodes("wo bu zai pang huang") && "我不再彷徨" in texts) {
                    assertEquals(code, "我不再彷徨", selection.firstPage.first().text)
                    assertEquals(texts.indexOf("我不再彷徨"), selection.firstPage.first().nativeIndex)
                }
                assertFalse(T9Spelling.displayComposition(
                    T9Spelling.preedit(code, selection.firstPage.first().pinyin).orEmpty(), true,
                ).any(Char::isDigit))
            }
        } finally {
            closeStore(); context.deleteDatabase("local_input.db")
        }
    }
    @Test fun `长句分段选择的学习在数据库重开后覆盖完整码和末字首字母`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val field = OfflineT9Candidates::class.java.getDeclaredField("store").apply { isAccessible = true }
        fun closeStore() {
            (field.get(OfflineT9Candidates) as? LocalInputStore)?.close()
            field.set(OfflineT9Candidates, null)
        }
        closeStore(); context.deleteDatabase("local_input.db")
        OfflineT9Candidates.init(context)
        val target = "我不再彷徨"
        try {
            val tracker = T9CommitTracker()
            repeat(3) {
                tracker.segment("9628924726448264", "我不再", "wo bu zai", null)
                tracker.segment("", "彷徨", "pang huang", target)
                val selected = tracker.consumeSelection(target, true)
                assertNotNull(selected)
                assertEquals("wo bu zai pang huang", selected!!.pinyin)
                OfflineT9Candidates.learn(selected.code, selected.text, selected.pinyin)
                assertNull("同次提交不能重复记忆", tracker.consumeSelection(target, true))
            }
            closeStore()
            OfflineT9Candidates.init(context)
            for (code in listOf("962892472644", "9628924726448264")) {
                // 没有原生整句也必须从个人词库召回；这不是为该句添加公共白名单。
                assertEquals(target, OfflineT9Candidates.select(
                    code, listOf("我不在", "我不再", "我"), listOf("wo bu zai", "wo bu zai", "wo"),
                ).firstPage.first().text)
            }
        } finally {
            closeStore(); context.deleteDatabase("local_input.db")
        }
    }

}
