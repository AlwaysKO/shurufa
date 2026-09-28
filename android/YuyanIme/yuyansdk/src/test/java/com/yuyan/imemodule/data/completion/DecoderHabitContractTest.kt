package com.yuyan.imemodule.data.completion

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.data.collect.DictionaryAddition
import com.yuyan.imemodule.data.collect.DictionaryHabit
import com.yuyan.imemodule.data.collect.DictionaryRecord
import com.yuyan.imemodule.data.collect.LocalInputStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 只验证解码候选变化后的既有个人习惯契约，不模拟原生整句生成成功。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DecoderHabitContractTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val code = "948336426"
    private val sentence = RankedCandidate("煮的面", "zhu de mian")
    private val name = RankedCandidate("朱逢博", "zhu feng bo")

    private fun closeStore() {
        OfflineT9Candidates::class.java.getDeclaredField("store").apply {
            isAccessible = true
            (get(OfflineT9Candidates) as? LocalInputStore)?.close()
            set(OfflineT9Candidates, null)
        }
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

    private fun candidates(native: List<RankedCandidate>, typedCode: String = code) =
        OfflineT9Candidates.select(typedCode, native.map { it.text }, native.map { it.pinyin }).firstPage

    private inline fun withStore(block: (LocalInputStore) -> Unit) {
        val store = LocalInputStore(context)
        try { block(store) } finally { store.close() }
    }

    @Test fun `基础候选顺序改变不能覆盖最近真实同码改选及锁音索引`() {
        withStore { store ->
            repeat(8) { store.learn(code, name.text, pinyin = name.pinyin) }
            store.writableDatabase.execSQL("UPDATE learned_input SET last_used=last_used-60000")
            store.learn(code, sentence.text, pinyin = sentence.pinyin)
        }
        for (native in listOf(listOf(name, sentence), listOf(sentence, name))) {
            val expectedIndex = native.indexOf(sentence)
            assertEquals(sentence.text, candidates(native).first().text)
            assertEquals(expectedIndex, candidates(native).first().nativeIndex)
            val locked = OfflineT9Candidates.rankNative(native.mapIndexed { index, item ->
                item.copy(nativeIndex = index)
            }, native.size)
            assertEquals(sentence.text, locked.firstPage.first().text)
            assertEquals(expectedIndex, locked.firstPage.first().nativeIndex)
        }
        withStore { store ->
            assertEquals(1L, store.learned(code).single { it.text == sentence.text }.count)
            assertEquals(8L, store.learned(code).single { it.text == name.text }.count)
        }
    }

    @Test fun `模型未召回的非日用个人词重开后仍凭旧习惯优先且不越读音边界`() {
        val native = listOf(sentence.copy(nativeIndex = 0), name.copy(nativeIndex = 1))
        assertEquals(sentence.text, OfflineT9Candidates.rankNative(native, native.size).firstPage.first().text)
        withStore { store ->
            repeat(8) { store.learn(code, name.text, pinyin = name.pinyin) }
            store.writableDatabase.execSQL("UPDATE learned_input SET last_used=last_used-?",
                arrayOf<Any>(PersonalCandidateRanker.HALF_LIFE_MS / 2))
        }
        closeStore()
        OfflineT9Candidates.init(context)
        val locked = OfflineT9Candidates.rankNative(native, native.size).firstPage.first()
        assertEquals(name.text, locked.text)
        assertEquals(1, locked.nativeIndex)
        val result = candidates(listOf(sentence))
        assertEquals(name.text, result.first().text)
        assertEquals(name.pinyin, result.first().pinyin)
        assertNull(result.first().nativeIndex)
        assertEquals(name.text, candidates(emptyList(), code.dropLast(1)).first().text)
        assertFalse(candidates(emptyList(), "948336427").any { it.text == name.text })
        withStore { store ->
            assertEquals(8L, store.learned(code).single().count)
            assertTrue(store.learned(code.dropLast(1)).isEmpty())
            assertTrue(store.learned("948336427").isEmpty())
        }
    }

    @Test fun `后台偏好标志改变默认排序但后续真实改选可优先且不制造点击`() {
        val typedCode = "966"
        val usual = RankedCandidate("我们", "wo men")
        val preferred = RankedCandidate("我哦", "wo o")
        val native = listOf(usual, preferred)
        assertEquals(usual.text, candidates(native, typedCode).first().text)
        withStore { store ->
            store.mergeDictionaryAdditions(listOf(DictionaryAddition(1, preferred.text, preferred.pinyin)))
        }
        assertEquals(usual.text, candidates(native, typedCode).first().text)
        withStore { store ->
            store.mergeDictionaryAdditions(listOf(DictionaryAddition(2, preferred.text, preferred.pinyin, preferred = true)))
        }
        assertEquals(preferred.text, candidates(native, typedCode).first().text)
        withStore { store ->
            assertTrue(store.learned(typedCode).isEmpty())
            assertTrue(store.dictionaryExport().none { it.kind == "choice" })
            store.learn(typedCode, usual.text, pinyin = usual.pinyin)
        }
        closeStore()
        OfflineT9Candidates.init(context)
        assertEquals(usual.text, candidates(native.reversed(), typedCode).first().text)
        assertTrue(candidates(listOf(usual), typedCode).any { it.text == preferred.text })
        withStore { store ->
            assertEquals(listOf(usual.text), store.learned(typedCode).map { it.text })
            assertEquals(1L, store.learned(typedCode).single().count)
        }
    }

    @Test fun `远端专名真实习惯在模型不召回时仍有效且不转成本机点击`() {
        val native = listOf(sentence.copy(nativeIndex = 0), name.copy(nativeIndex = 1))
        withStore { store ->
            store.mergeDictionaryAdditions(listOf(DictionaryAddition(1, name.text, name.pinyin)))
        }
        assertEquals(sentence.text, OfflineT9Candidates.rankNative(native, native.size).firstPage.first().text)
        withStore { store ->
            val record = DictionaryRecord("choice", name.text, code, "", "selection", 3, 3.0,
                System.currentTimeMillis(), "old-device", 1)
            store.mergeDictionaryHabits(listOf(DictionaryHabit(1, record)), "this-device")
        }
        repeat(2) {
            val locked = OfflineT9Candidates.rankNative(native, native.size).firstPage.first()
            assertEquals(name.text, locked.text)
            assertEquals(1, locked.nativeIndex)
            assertEquals(name.text, candidates(listOf(sentence)).first().text)
            withStore { store ->
                assertEquals(3L, store.learned(code).single().count)
                assertTrue(store.dictionaryExport().none { it.kind == "choice" })
                assertTrue(store.reportTargets().isEmpty())
                store.readableDatabase.rawQuery("SELECT COUNT(*) FROM learned_input", null).use { cursor ->
                    cursor.moveToFirst()
                    assertEquals(0L, cursor.getLong(0))
                }
            }
            closeStore()
            OfflineT9Candidates.init(context)
        }
    }
    @Test fun `煮的面分段成功一次后无需原生召回仍能重开记住`() {
        val tracker = T9CommitTracker()
        tracker.segment(code, "煮", "zhu", null)
        tracker.segment("", "的", "de", null)
        tracker.segment("", "面", "mian", "煮的面")
        val selection = requireNotNull(tracker.consumeSelection("煮的面", true))
        assertEquals("zhu de mian", selection.pinyin)
        OfflineT9Candidates.learn(selection)
        closeStore()
        OfflineT9Candidates.init(context)
        val recalled = candidates(listOf(name))
        assertEquals("煮的面", recalled.first().text)
        assertNull("未依赖原生引擎生成整句", recalled.first().nativeIndex)
        withStore { store -> assertEquals(1L, store.learned(code).single { it.text == "煮的面" }.count) }
    }
}
