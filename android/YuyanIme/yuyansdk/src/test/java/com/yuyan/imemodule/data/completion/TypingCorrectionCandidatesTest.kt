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
class TypingCorrectionCandidatesTest {
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
    @After fun cleanup() = closeStore()

    @Test fun `全键拼音交换漏键多键仍能提供真实词典词`() {
        for (code in listOf("nansahn", "nanshn", "nanshhan")) {
            val result = OfflineT9Candidates.select(code).firstPage
            assertTrue(code + result.map { it.text }, result.any { it.text == "南山" })
        }
    }
    @Test fun `九宫格相邻交换可召回且不伪造拼音与原生索引`() {
        val result = OfflineT9Candidates.select("6267246").firstPage
        val candidate = result.firstOrNull { it.text == "南山" }
        assertNotNull(candidate)
        assertEquals("nan shan", candidate!!.pinyin)
        assertNull(candidate.nativeIndex)
    }
    @Test fun `纠错不是针对南山的编码例外`() {
        for ((code, word) in listOf("beijign" to "北京", "shangahi" to "上海", "pengoyu" to "朋友")) {
            assertTrue(code, OfflineT9Candidates.select(code).firstPage.any { it.text == word })
        }
    }
    @Test fun `全键首音节内部错位和漏键也可纠正`() {
        for (input in listOf("bejing", "biejing")) {
            val result = OfflineT9Candidates.select(input).firstPage
            assertTrue(input + result.map { it.text }, result.any { it.text == "北京" && it.inputMatch?.kind == InputMatchKind.TYPO_CORRECTION })
        }
    }
    @Test fun `已有正常双字混拼时不注入纠错候选`() {
        for (code in listOf("424364", "924364", "524364")) {
            val result = OfflineT9Candidates.select(code).firstPage
            assertTrue(code, result.any { it.inputMatch?.kind == InputMatchKind.INITIAL_FULL_WORD })
            assertFalse(code + result.map { it.text }, result.any { it.inputMatch?.kind == InputMatchKind.TYPO_CORRECTION })
        }
    }
    @Test fun `已有正常词和锁音时不注入纠错建议`() {
        for (code in listOf("6267", "nans", "beijing", "nan's")) {
            assertFalse(code, OfflineT9Candidates.select(code).firstPage.any { it.inputMatch?.kind == InputMatchKind.TYPO_CORRECTION })
        }
    }
    @Test fun `纠错保留原生索引但不会把错误按键交给学习`() {
        for (code in listOf("nansahn", "6267246")) {
            val candidate = OfflineT9Candidates.select(code, listOf("南山"), listOf("nan shan")).firstPage.first { it.text == "南山" }
            assertEquals(0, candidate.nativeIndex)
            assertEquals(InputMatchKind.TYPO_CORRECTION, candidate.inputMatch?.kind)
            val tracker = T9CommitTracker()
            tracker.segment(code, candidate.text, candidate.pinyin, candidate.text, matchKind = candidate.inputMatch?.kind)
            assertNull(tracker.consumeSelection(candidate.text, true))
            tracker.selected(code, candidate.text, candidate.pinyin, matchKind = candidate.inputMatch?.kind)
            assertNull(tracker.consumeSelection(candidate.text, true))
        }
    }
    @Test fun `纠错数量有界且不猜测两个错误或改动前三键`() {
        val dictionary = T9Lexicon.parse("南山\tnan shan\t522\n".reader())
        assertTrue(OfflineT9Candidates.select("shangahi").firstPage.count {
            it.inputMatch?.kind == InputMatchKind.TYPO_CORRECTION
        } <= 2)
        assertTrue(dictionary.queryCorrections("nansxxn").isEmpty())
        assertTrue(dictionary.queryCorrections("manshan").isEmpty())
        assertTrue(dictionary.queryCorrections("nan").isEmpty())
        assertTrue(dictionary.queryCorrections("nanshan").isEmpty())
    }
}
