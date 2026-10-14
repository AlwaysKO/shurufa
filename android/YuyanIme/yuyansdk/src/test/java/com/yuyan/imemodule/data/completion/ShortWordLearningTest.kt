package com.yuyan.imemodule.data.completion

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.data.collect.LocalInputStore
import com.yuyan.imemodule.data.collect.DictionarySnapshot
import com.yuyan.imemodule.data.collect.DictionaryPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ShortWordLearningTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var store: LocalInputStore
    @Before fun setup() {
        context.deleteDatabase("local_input.db")
        store = LocalInputStore(context)
    }
    @After fun close() = store.close()

    @Test fun `真实两键选择保留读音可重查且不伪造全码选择`() {
        store.learn("25", "北京", pinyin = "bei jing")
        assertTrue(store.personalWords("25").any { it.text == "北京" && it.pinyin == "bei jing" })
        assertTrue(store.personalWords("bj").any { it.text == "北京" })
        assertFalse(store.personalWords("nh").any { it.text == "北京" })
        assertEquals(1, store.learned("25").first { it.text == "北京" }.count.toInt())
        assertTrue(store.learned("2345464").isEmpty())
    }

    @Test fun `禁用原生首项后保留前两个可见原生候选再补简拼`() {
        store.applyDictionarySnapshot(DictionarySnapshot("group", "a".repeat(64), emptyList(),
            listOf(DictionaryPolicy("你", "disabled"))), "self")
        val field = OfflineT9Candidates::class.java.getDeclaredField("store").apply { isAccessible = true }
        val previous = field.get(OfflineT9Candidates)
        try {
            field.set(OfflineT9Candidates, store)
            OfflineT9Candidates.init(context)
            val result = OfflineT9Candidates.select("64", listOf("你", "拟", "泥"), listOf("ni", "ni", "ni"))
            assertEquals(listOf("拟", "泥"), result.firstPage.take(2).map { it.text })
            assertEquals(listOf(1, 2), result.firstPage.take(2).map { it.nativeIndex })
        } finally { field.set(OfflineT9Candidates, previous) }
    }
}
