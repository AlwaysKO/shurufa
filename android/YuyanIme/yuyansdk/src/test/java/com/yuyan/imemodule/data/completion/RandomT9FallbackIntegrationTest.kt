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
class RandomT9FallbackIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val code = "64364526949767646646976666"
    private fun closeStore() {
        val field = OfflineT9Candidates::class.java.getDeclaredField("store").apply { isAccessible = true }
        (field.get(OfflineT9Candidates) as? LocalInputStore)?.close()
        field.set(OfflineT9Candidates, null)
    }
    @Before fun setup() { closeStore(); context.deleteDatabase("local_input.db"); OfflineT9Candidates.init(context) }
    @After fun cleanup() { closeStore(); context.deleteDatabase("local_input.db") }
    @Test fun `乱串只有原生开头单字时首屏出现多个全码组合`() {
        val results = OfflineT9Candidates.select(code, listOf("捏", "灭"), listOf("nie", "mie")).firstPage
        val full = results.filter { it.inputMatch?.kind?.name == "SENTENCE_FALLBACK" }
        assertTrue(full.size >= 2)
        assertEquals(full.first(), results.first())
        full.forEach {
            assertTrue(it.text.length >= 5)
            assertEquals(it.text.length, it.pinyin.split(' ').size)
            assertEquals(code, T9Lexicon.digits(it.inputMatch!!.preedit.replace("'", "")))
            assertNull(it.nativeIndex)
        }
    }
    @Test fun `正常整句和同码合理首选保持原生索引且不插入回退`() {
        for ((keys, text, reading) in listOf(
            Triple("4363865494", "很多利益", "hen duo li yi"),
            Triple("9628924726448264", "我不再彷徨", "wo bu zai pang huang"),
        )) {
            val results = OfflineT9Candidates.select(keys, listOf(text), listOf(reading)).firstPage
            assertEquals(text, results.first().text)
            assertEquals(0, results.first().nativeIndex)
            assertFalse(results.any { it.inputMatch?.kind?.name == "SENTENCE_FALLBACK" })
        }
    }
    @Test fun `锁音和短码不走随机长串回退`() {
        assertFalse(OfflineT9Candidates.select("6267", listOf("男人"), listOf("nan ren")).firstPage.any {
            it.inputMatch?.kind?.name == "SENTENCE_FALLBACK"
        })
        assertFalse(OfflineT9Candidates.selectLocked("nie'mi'kan'xi'w'po'po'gong'o'w'po'mo'o", listOf("捏"), listOf("nie")).firstPage.any {
            it.inputMatch?.kind?.name == "SENTENCE_FALLBACK"
        })
    }
    @Test fun `超过旧三十键仍有完整组合且禁词不藏进生成句`() {
        val longCode = code.repeat(4)
        val longResults = OfflineT9Candidates.select(longCode, emptyList(), emptyList()).firstPage
        assertTrue(longResults.size >= 2)
        longResults.forEach { assertEquals(longCode, T9Lexicon.digits(it.inputMatch!!.preedit.replace("'", ""))) }
        val blocked = OfflineT9Candidates.select(code, emptyList(), emptyList()).firstPage.first().text
        val store = LocalInputStore(context)
        try {
            store.applyDictionarySnapshot(com.yuyan.imemodule.data.collect.DictionarySnapshot("group", "a".repeat(64), emptyList(),
                listOf(com.yuyan.imemodule.data.collect.DictionaryPolicy(blocked, "disabled"))), "self")
            assertFalse(OfflineT9Candidates.select(code, emptyList(), emptyList()).firstPage.any { blocked in it.text })
        } finally { store.close() }
    }

    @Test fun `自动组合选中也不把未经正常拼写验证的句子记成个人词`() {
        val tracker = T9CommitTracker()
        val kind = InputMatchKind.valueOf("SENTENCE_FALLBACK")
        tracker.selected(code, "任意组合", "ren yi zu he", matchKind = kind)
        assertNull(tracker.consumeSelection("任意组合", true))
    }
}
