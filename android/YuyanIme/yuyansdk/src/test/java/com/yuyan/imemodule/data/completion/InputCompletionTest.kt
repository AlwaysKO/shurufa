package com.yuyan.imemodule.data.completion

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.data.collect.LocalInputStore
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class InputCompletionTest {
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

    @Test fun `三键简拼无需原生候选也能召回口语和地名`() {
        val result = OfflineT9Candidates.select("559", emptyList(), emptyList())
        assertTrue(result.firstPage.take(3).any { it.text == "就可以" })
        assertTrue(result.firstPage.any { it.text == "良口镇" })
        assertEquals("jiu ke yi", result.firstPage.first { it.text == "就可以" }.pinyin)
    }

    @Test fun `原生简拼首屏后页都保留实际索引`() {
        val result = OfflineT9Candidates.select("559", listOf("就可以"), listOf("jiu ke yi"))
        assertEquals(0, result.firstPage.firstOrNull { it.text == "就可以" }?.nativeIndex)
        assertEquals(listOf(1), result.appendNativePage(listOf("美国最高法院", "良口镇"), "559",
            listOf("mei guo zui gao fa yuan", "liang kou zhen")))
        assertEquals(2, result.at(result.firstPage.size)?.nativeIndex)
    }

    @Test fun `固定长句在第四字未打完时两种键盘都可补全`() {
        for (code in listOf("feiliuzhix", "3345489449")) {
            val result = OfflineT9Candidates.select(code, emptyList(), emptyList()).firstPage
            assertTrue(code, result.take(8).any { it.text == "飞流直下三千尺" })
            assertTrue(code, result.any { it.text == "飞流直下" })
        }
    }

    @Test fun `短前缀和错误续输不强行补出诗句且退格恢复`() {
        for (code in listOf("fei", "334", "feiliuzhixb", "33454894492")) {
            assertFalse(code, OfflineT9Candidates.select(code).firstPage.any { it.text == "飞流直下三千尺" })
        }
        assertTrue(OfflineT9Candidates.select("3345489449").firstPage.any { it.text == "飞流直下三千尺" })
    }

    @Test fun `简拼真实选择重开后可召回且不伪造完整码次数`() {
        OfflineT9Candidates.learn("559", "良口镇", "liang kou zhen")
        closeStore()
        OfflineT9Candidates.init(context)
        assertEquals("良口镇", OfflineT9Candidates.select("559", emptyList(), emptyList()).firstPage.first().text)
        val db = LocalInputStore(context)
        try {
            assertEquals(1L, db.learned("559").single().count)
            assertTrue(db.learned(T9Lexicon.digits("liangkouzhen")).isEmpty())
            assertTrue(db.personalWords("559").any { it.text == "良口镇" })
        } finally { db.close() }
    }

    @Test fun `全键前缀选择保存完整读音但只记录真实输入码`() {
        val code = "feiliuzhix"
        val text = "飞流直下三千尺"
        OfflineT9Candidates.learn(code, text, "fei liu zhi xia san qian chi")
        closeStore()
        OfflineT9Candidates.init(context)
        val db = LocalInputStore(context)
        try {
            assertEquals(1L, db.learned(code).single().count)
            assertTrue(db.learned("feiliuzhixiasanqianchi").isEmpty())
            assertEquals("fei liu zhi xia san qian chi", db.personalWords(code).single { it.text == text }.pinyin)
            assertTrue(db.personalWords("feiliuzhixb").none { it.text == text })
        } finally { db.close() }
        assertTrue(OfflineT9Candidates.select(code).firstPage.any { it.text == text })
    }

    @Test fun `长词纯简拼和内部扩写旧反例仍过滤`() {
        assertFalse(OfflineT9Candidates.select("649439", listOf("美国最高法院"),
            listOf("mei guo zui gao fa yuan")).firstPage.any { it.text == "美国最高法院" })
        assertFalse(OfflineT9Candidates.select("284269", listOf("长条形"),
            listOf("chang tiao xing")).firstPage.any { it.text == "长条形" })
    }

    @Test fun `两个公共补全不能挡住个人已选择的第三句`() {
        val code = T9Lexicon.digits("ershisiq")
        val text = "二十四桥仍在"
        OfflineT9Candidates.learn(code, text, "er shi si qiao reng zai")
        closeStore()
        OfflineT9Candidates.init(context)
        val candidates = OfflineT9Candidates.select(code, emptyList(), emptyList()).firstPage
        assertEquals(text, candidates.first().text)
        assertTrue(candidates.count { it.inputMatch?.kind == InputMatchKind.PHRASE_PREFIX } <= 2)
    }
}
