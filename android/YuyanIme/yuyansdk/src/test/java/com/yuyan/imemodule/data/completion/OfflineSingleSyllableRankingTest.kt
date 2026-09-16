package com.yuyan.imemodule.data.completion

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.data.collect.LocalInputStore
import com.yuyan.inputmethod.util.T9Spelling
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OfflineSingleSyllableRankingTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun closeStore() {
        val field = OfflineT9Candidates::class.java.getDeclaredField("store").apply { isAccessible = true }
        (field.get(OfflineT9Candidates) as? LocalInputStore)?.close()
        field.set(OfflineT9Candidates, null)
    }

    @Before fun setup() {
        closeStore()
        context.deleteDatabase("local_input.db")
        OfflineT9Candidates.init(context)
    }

    @After fun cleanup() {
        closeStore()
        context.deleteDatabase("local_input.db")
    }

    @Test fun `完整chuan优先且保留同码词和原生单字相对顺序`() {
        val texts = listOf("哀叹", "刺探", "窗", "穿", "传", "船")
        val readings = listOf("ai tan", "ci tan", "chuang", "chuan", "chuan", "chuan")
        val result = OfflineT9Candidates.select("24826", texts, readings)
        assertEquals(listOf("穿", "传", "船"), result.firstPage.take(3).map { it.text })
        assertTrue(result.firstPage.any { it.text == "哀叹" })
        assertTrue(result.firstPage.any { it.text == "刺探" })
        assertTrue(result.firstPage.any { it.text == "窗" })
        assertEquals("chuan", T9Spelling.preedit("24826", result.firstPage.first().pinyin))
        assertEquals(3, result.at(0)?.nativeIndex)
        assertEquals(4, result.at(1)?.nativeIndex)
        assertEquals(5, result.at(2)?.nativeIndex)
        assertEquals(listOf(0), result.appendNativePage(listOf("串"), "24826", listOf("chuan")))
        assertEquals(6, result.at(result.firstPage.size)?.nativeIndex)
    }

    @Test fun `完整长音节优先于只覆盖前缀的单字及同码多字词`() {
        for ((reading, word, other, otherReading) in listOf(
            listOf("chuang", "窗", "穿", "chuan"),
            listOf("shuang", "双", "书", "shu"),
            listOf("zhuang", "装", "西塘", "xi tang"),
        )) {
            val code = T9Lexicon.digits(reading)
            val result = OfflineT9Candidates.select(code, listOf(other, word), listOf(otherReading, reading))
            assertEquals(reading, word, result.firstPage.first().text)
            assertEquals(1, result.firstPage.first().nativeIndex)
            assertEquals(reading, T9Spelling.preedit(code, result.firstPage.first().pinyin))
            assertTrue(result.firstPage.any { it.text == other })
        }
    }

    @Test fun `逐键输入删除只在精确覆盖时提升而不提前补全音节`() {
        val texts = listOf("哀叹", "窗", "穿")
        val readings = listOf("ai tan", "chuang", "chuan")
        for (code in listOf("2482", "24826", "248264", "24826", "2482")) {
            val result = OfflineT9Candidates.select(code, texts, readings).firstPage
            when (code) {
                "24826" -> assertEquals("穿", result.first().text)
                "248264" -> assertEquals("窗", result.first().text)
                else -> {
                    assertTrue(result.indexOfFirst { it.text == "哀叹" } <
                        result.indexOfFirst { it.text == "穿" })
                    assertNotEquals("窗", result.first().text)
                }
            }
        }
    }

    @Test fun `继续输入多音节时完整短单字不再获得优先级`() {
        val result = OfflineT9Candidates.select("2482694", listOf("穿", "穿衣"), listOf("chuan", "chuan yi"))
        val words = result.firstPage.map { it.text }
        assertTrue(words.containsAll(listOf("穿衣", "穿")))
        assertTrue(words.indexOf("穿衣") < words.indexOf("穿"))
        assertNotEquals("穿", words.first())
    }

    @Test fun `明确学习仍可将同码多字词置顶且不会删除完整单音节`() {
        val texts = listOf("哀叹", "穿")
        val readings = listOf("ai tan", "chuan")
        assertEquals("穿", OfflineT9Candidates.select("24826", texts, readings).firstPage.first().text)
        repeat(3) { OfflineT9Candidates.learn("24826", "哀叹", "ai tan") }
        closeStore()
        OfflineT9Candidates.init(context)
        val result = OfflineT9Candidates.select("24826", texts, readings)
        assertEquals("哀叹", result.firstPage.first().text)
        assertTrue(result.firstPage.any { it.text == "穿" })
        assertTrue("单字不应进入跨码补全学习", T9Spelling.completionCodes("chuan").isEmpty())
    }

    @Test fun `显式分词或锁定字母拼音不进入新的数字码分组`() {
        for (code in listOf("24'826", "chuan")) {
            val result = OfflineT9Candidates.select(code, listOf("传", "穿"), listOf("chuan", "chuan"))
            assertEquals(listOf("传", "穿"), result.firstPage.take(2).map { it.text })
        }
    }
}
