package com.yuyan.imemodule.data.completion

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.data.collect.LocalInputStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ScreenshotCandidateRegressionTest {
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

    @Test fun `无历史时等你懂了优于同码自动乱句且末字补全可用`() {
        for (code in listOf("336464366453", "33646436645")) {
            val selection = OfflineT9Candidates.select(code,
                listOf("的明懂了", "灯灭", "等你", "蜂蜜", "🍯"),
                listOf("de'ming'dong'le", "deng'mie", "deng'ni", "feng'mi", ""))
            assertEquals(code, "等你懂了", selection.firstPage.first().text)
            assertEquals("deng ni dong le", selection.firstPage.first().pinyin)
            assertFalse(selection.firstPage.any { it.text == "的明懂了" })
            assertEquals(listOf(1), selection.appendNativePage(listOf("的明懂了", "等你懂了"), code,
                listOf("de ming dong le", "deng ni dong le")))
            assertEquals(6, selection.at(selection.firstPage.size)?.nativeIndex)
        }
    }

    @Test fun `5526无需历史即可召回看看且排在表情之前`() {
        for ((native, readings) in listOf(
            listOf("👀", "🚑") to listOf("", ""),
            listOf("👀", "看看", "就看", "浏览") to listOf("", "kan kan", "jiu kan", "liu lan"),
            emptyList<String>() to emptyList(),
        )) {
            val selection = OfflineT9Candidates.select("5526", native, readings)
            assertEquals("看看", selection.firstPage.first().text)
            assertEquals("k'kan", selection.firstPage.first().inputMatch?.preedit)
            assertTrue(selection.firstPage.any { it.text == "就看" })
            assertTrue(selection.firstPage.any { it.text == "浏览" })
            if ("看看" in native) assertEquals(1, selection.firstPage.first().nativeIndex)
        }
    }

    @Test fun `双字混拼分页和成功提交保留真实编码读音`() {
        val selection = OfflineT9Candidates.select("5526", listOf("👀"), listOf(""))
        assertEquals(listOf(0), selection.appendNativePage(listOf("就看", "假词"), "5526", listOf("jiu kan", "jia ci")))
        assertEquals(1, selection.at(selection.firstPage.size)?.nativeIndex)
        val tracker = T9CommitTracker()
        tracker.segment("5526", "看看", "kan kan", "看看")
        assertEquals("5526", tracker.consumeSelection("看看", true)?.code)
        tracker.selected("5526", "看看", "kan kan")
        assertNull(tracker.consumeSelection("看看", false))
    }

    @Test fun `双字混拼真实选择在重开后仍优先但不伪造全码点击`() {
        OfflineT9Candidates.learn("5526", "浏览", "liu lan")
        closeStore()
        OfflineT9Candidates.init(context)
        assertEquals("浏览", OfflineT9Candidates.select("5526").firstPage.first().text)
        val store = LocalInputStore(context)
        try {
            assertEquals(1L, store.learned("5526").first { it.text == "浏览" }.count)
            assertFalse(store.learned("548526").any { it.text == "浏览" })
        } finally { store.close() }
    }

    @Test fun `混拼补充不越过完整单字和既有整词且支持退格再输入`() {
        val single = OfflineT9Candidates.select("74826", listOf("栓", "拴"), listOf("shuan", "shuan"))
        assertEquals(listOf("栓", "拴"), single.firstPage.take(2).map { it.text })
        assertFalse("已有完整候选时不额外混入双字首字简拼", single.firstPage.any { it.text == "舒缓" })
        for (code in listOf("552", "5526", "552", "5526")) {
            val selection = OfflineT9Candidates.select(code)
            if (code == "5526") assertEquals("看看", selection.firstPage.first().text)
            else assertFalse(selection.firstPage.any { it.text == "看看" })
        }
        assertEquals("看看", OfflineT9Candidates.select("526526").firstPage.first().text)
    }

    @Test fun `未知双字不能只凭简拼扩写且其他整句回退不变`() {
        val unknown = OfflineT9Candidates.select("5526", listOf("假堪"), listOf("jia kan"))
        assertFalse(unknown.firstPage.any { it.text == "假堪" })
        assertTrue(unknown.appendNativePage(listOf("假堪"), "5526", listOf("jia kan")).isEmpty())
        val sentence = OfflineT9Candidates.select("9628924726448264", listOf("我不再彷徨"), listOf("wo bu zai pang huang"))
        assertEquals("我不再彷徨", sentence.firstPage.first().text)
    }

    @Test fun `双字补充不修改分词锁音和字母输入的原生索引`() {
        for (code in listOf("5'526", "kkan")) {
            val selection = OfflineT9Candidates.select(code, listOf("看看", "就看"), listOf("kan kan", "jiu kan"))
            assertEquals(listOf("看看", "就看"), selection.firstPage.take(2).map { it.text })
            assertEquals(listOf(0, 1), selection.firstPage.take(2).map { it.nativeIndex })
        }
    }

    @Test fun `同时符合旧末字前缀时保持就会的排序和显示`() {
        val selection = OfflineT9Candidates.select("5484")
        val words = selection.firstPage.map { it.text }
        val word = selection.firstPage.first { it.text == "就会" }
        assertNull(word.inputMatch)
        assertEquals("jiu'h", com.yuyan.inputmethod.util.T9Spelling.preedit("5484", word.pinyin))
        assertTrue(words.indexOf("就会") < words.indexOf("几天"))
        assertTrue(words.indexOf("就会") < words.indexOf("就好"))
        assertNull(InputSpellingMatch.match("5484", "jiu hui"))
        assertNull(InputSpellingMatch.match("6242", "nai cha"))
        assertNull(InputSpellingMatch.match("3343", "fei die"))
    }
}
