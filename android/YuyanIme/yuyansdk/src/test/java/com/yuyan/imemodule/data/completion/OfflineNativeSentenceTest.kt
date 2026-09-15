package com.yuyan.imemodule.data.completion

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.data.collect.LocalInputStore
import com.yuyan.inputmethod.util.T9Spelling
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OfflineNativeSentenceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val reading = "wo'bu'zai'pang'huang"
    private val codes = listOf("9628924726448264", "962892472644")
    private fun closeStore() {
        val field = OfflineT9Candidates::class.java.getDeclaredField("store").apply { isAccessible = true }
        (field.get(OfflineT9Candidates) as? LocalInputStore)?.close()
        field.set(OfflineT9Candidates, null)
    }
    @Before fun setup() { closeStore(); context.deleteDatabase("local_input.db"); OfflineT9Candidates.init(context) }
    @After fun cleanup() { closeStore(); context.deleteDatabase("local_input.db") }

    @Test fun `未收录整句在完整码和末字首字母下一次可选并保留原生索引`() {
        for (code in codes) {
            val selection = OfflineT9Candidates.select(code,
                listOf("我不再彷徨", "我不在", "我不再"), listOf(reading, "wo'bu'zai", "wo'bu'zai"))
            assertEquals(code, "我不再彷徨", selection.firstPage.first().text)
            assertEquals(0, selection.firstPage.first().nativeIndex)
            assertEquals(reading, selection.firstPage.first().pinyin)
            assertFalse(T9Spelling.preedit(code, selection.firstPage.first().pinyin)!!.any { it.isDigit() })
            assertEquals(listOf(1), selection.appendNativePage(listOf("我不再彷徨😀", "我不在彷徨"), code, listOf(reading, reading)))
            assertEquals(4, selection.at(selection.firstPage.size)?.nativeIndex)
        }
    }

    @Test fun `已有可信完整词时不放回未知乱串`() {
        val selection = OfflineT9Candidates.select("9664337", listOf("总额而", "总额"), listOf("zong'e'er", "zong'e"))
        assertEquals("用得上", selection.firstPage.first().text)
        assertFalse(selection.firstPage.any { it.text == "总额而" })
        assertTrue(selection.appendNativePage(listOf("总额而"), "9664337", listOf("zong'e'er")).isEmpty())
    }

    @Test fun `整句回退不能绕过字音匹配与纯中文限制`() {
        for ((text, pinyin) in listOf(
            "我不再彷徨" to "wo'bu'zai", "我不再彷徨" to "wo'bu'zai'pang",
            "我不再彷徨" to "", "我不再彷徨😀" to reading,
            "我不再彷徨" to "wo'bu'zai'pang'huang'a", "我不再彷徨" to "ni'bu'zai'pang'huang",
        )) {
            val selection = OfflineT9Candidates.select(codes.first(), listOf(text), listOf(pinyin))
            assertFalse("$text $pinyin", selection.firstPage.any { it.text == text })
            assertTrue(selection.appendNativePage(listOf(text), codes.first(), listOf(pinyin)).isEmpty())
        }
        assertFalse(OfflineT9Candidates.select("929726448264", listOf("我不再彷徨"), listOf(reading)).firstPage.any { it.text == "我不再彷徨" })
        assertFalse(OfflineT9Candidates.select(codes.first(), listOf("我不再彷徨"), null).firstPage.any { it.text == "我不再彷徨" })
    }

    @Test fun `整句仍可按个人选择在重开后更换首位`() {
        val texts = listOf("我不在彷徨", "我不再彷徨", "我不在")
        val readings = listOf(reading, reading, "wo'bu'zai")
        assertEquals("我不在彷徨", OfflineT9Candidates.select(codes.first(), texts, readings).firstPage.first().text)
        repeat(3) { OfflineT9Candidates.learn(codes.first(), "我不在彷徨", reading) }
        closeStore(); OfflineT9Candidates.init(context)
        for (code in codes) {
            val selected = OfflineT9Candidates.select(code, texts, readings)
            assertEquals("我不在彷徨", selected.firstPage.first().text)
            assertTrue("先学错项后仍能选另一整句: $code", selected.firstPage.any { it.text == "我不再彷徨" })
            assertEquals(listOf(0), selected.appendNativePage(listOf("我不再彷徨"), code, listOf(reading)))
        }
        repeat(5) { OfflineT9Candidates.learn(codes.first(), "我不再彷徨", reading) }
        closeStore(); OfflineT9Candidates.init(context)
        for (code in codes) assertEquals("我不再彷徨", OfflineT9Candidates.select(code, texts, readings).firstPage.first().text)
    }
}
