package com.yuyan.imemodule.data.collect

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.data.completion.OfflineT9Candidates
import com.yuyan.imemodule.data.completion.RankedCandidate
import com.yuyan.inputmethod.RimeEngine
import com.yuyan.inputmethod.core.CandidateListItem
import com.yuyan.imemodule.data.completion.CandidateSelection
import com.yuyan.imemodule.service.DecodingInfo
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DictionaryCandidatePolicyTest {
    private inline fun <T> LocalInputStore.withStore(block: (LocalInputStore) -> T): T = try { block(this) } finally { close() }
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val field = OfflineT9Candidates::class.java.getDeclaredField("store").apply { isAccessible = true }
    private fun closeCandidates() {
        (field.get(OfflineT9Candidates) as? LocalInputStore)?.close()
        field.set(OfflineT9Candidates, null)
    }
    @Before fun setup() { closeCandidates(); context.deleteDatabase("local_input.db"); OfflineT9Candidates.init(context) }
    @After fun cleanup() { closeCandidates(); context.deleteDatabase("local_input.db") }
    private fun apply(status: String, text: String = "朱逢博") {
        LocalInputStore(context).withStore { it.applyDictionarySnapshot(DictionarySnapshot("group", "a".repeat(64), emptyList(), listOf(DictionaryPolicy(text,status))), "self") }
    }
    private fun texts(code: String) = OfflineT9Candidates.select(code, listOf("朱逢博", "珠峰"), listOf("zhu feng bo", "zhu feng")).firstPage.map { it.text }

    @Test fun `明确删除覆盖原生与个人学习并跨实例和重启生效恢复保留习惯`() {
        LocalInputStore(context).withStore { it.learn("948336426", "朱逢博", pinyin = "zhu feng bo"); it.learn("9483364", "珠峰", pinyin = "zhu feng") }
        assertTrue("朱逢博" in texts("948336426"))
        apply("deleted")
        assertFalse("朱逢博" in texts("948336426"))
        assertFalse("朱逢博" in texts("zhufengbo"))
        assertFalse("朱逢博" in texts("9"))
        closeCandidates(); OfflineT9Candidates.init(context)
        assertFalse("朱逢博" in texts("948336426"))
        LocalInputStore(context).withStore { store ->
            assertEquals(1L, store.learned("9483364").single().count)
            assertTrue(store.dictionaryExport().any { it.text == "朱逢博" })
            store.applyDictionarySnapshot(DictionarySnapshot("group", "b".repeat(64), emptyList(), emptyList()), "self")
        }
        assertFalse("朱逢博" in texts("948336426"))
        apply("enabled")
        assertTrue("朱逢博" in texts("948336426"))
        LocalInputStore(context).withStore { assertEquals(1L, it.learned("948336426").single().count) }
    }

    @Test fun `锁音和后续页去除禁词仍保留真正原生索引和重复字读音`() {
        apply("disabled")
        val selection = OfflineT9Candidates.rankNative(listOf(RankedCandidate("朱逢博", "zhu feng bo", 0), RankedCandidate("珠峰", "zhu feng", 1)), 2)
        assertEquals(listOf("珠峰"), selection.firstPage.map { it.text })
        assertEquals(1, selection.at(0)?.nativeIndex)
        assertEquals(listOf(1,2), selection.appendNativePage(listOf("朱逢博", "主峰", "主峰"), "", listOf("zhu feng bo", "zhu feng", "zhu feng")))
        assertEquals(3, selection.at(1)?.nativeIndex)
        assertEquals(4, selection.at(2)?.nativeIndex)
    }

    @Test fun `删除内置常用词同样生效且只匹配整个候选不删包含该字的词`() {
        apply("deleted", "我")
        val selection = OfflineT9Candidates.select("966", listOf("我", "我们"), listOf("wo", "wo men"))
        assertFalse(selection.firstPage.any { it.text == "我" })
        assertTrue(selection.firstPage.any { it.text == "我们" })
        assertEquals(listOf(1), selection.appendNativePage(listOf("我", "我们"), "966", listOf("wo", "wo men")))
    }

    @Test fun `候选显示后收到删除也不能通过旧项或联想提交`() {
        RimeEngine.clearCachedCompositionForSchemaSwitch()
        RimeEngine.showCandidates = listOf(CandidateListItem("zhu feng bo", "朱逢博"))
        apply("deleted")
        assertNull(RimeEngine.selectCandidate(0))
        RimeEngine.selectAssociation(0)
        assertEquals("", RimeEngine.preCommitText)
        RimeEngine.clearCachedCompositionForSchemaSwitch()
    }

    @Test fun `事务失败不改变屏蔽缓存并且重复查询仅返回同一内存集合`() {
        apply("deleted")
        val before = OfflineT9Candidates.blockedCandidateTexts()
        LocalInputStore(context).withStore { store ->
            // 两个相同主键制造恢复中途失败，策略不得提前发布。
            val record = DictionaryRecord("word", "珠峰", "", "zhu feng", "selection", 0, 0.0, 0, "other")
            assertTrue(runCatching {
                store.applyDictionarySnapshot(DictionarySnapshot("group", "c".repeat(64), listOf(record,record), listOf(DictionaryPolicy("朱逢博","enabled"))), "self")
            }.isFailure)
            val cache = store.blockedCandidateTexts()
            store.close()
            repeat(10_000) { assertSame(cache, store.blockedCandidateTexts()) }
        }
        assertEquals(before, OfflineT9Candidates.blockedCandidateTexts())
        assertFalse("朱逢博" in texts("948336426"))
    }

    @Test fun `策略写入本身失败不得静默成功`() {
        LocalInputStore(context).withStore { store ->
            store.writableDatabase.execSQL("CREATE TRIGGER reject_policy BEFORE INSERT ON dictionary_policy BEGIN SELECT RAISE(ABORT, 'test failure'); END")
            assertTrue(runCatching { apply("deleted") }.isFailure)
            assertTrue(store.blockedCandidateTexts().isEmpty())
        }
    }

    @Test fun `后续页展示之后收到删除仍不能提交`() {
        RimeEngine.clearCachedCompositionForSchemaSwitch()
        val selection = CandidateSelection(listOf(RankedCandidate("珠峰", "zhu feng", 0)),1)
        selection.appendNativePage(listOf("朱逢博"), "", listOf("zhu feng bo"))
        RimeEngine::class.java.getDeclaredField("personalCandidates").apply { isAccessible=true; set(RimeEngine,selection) }
        RimeEngine.showCandidates = listOf(CandidateListItem("zhu feng", "珠峰"))
        apply("deleted")
        try { assertNull(RimeEngine.selectCandidate(1)) }
        finally { RimeEngine.clearCachedCompositionForSchemaSwitch() }
    }

    @Test fun `手写自定义和远端候选缓存也排除明确禁词`() {
        apply("deleted")
        for (comment in listOf("", "📋", "补全")) {
            DecodingInfo.cacheCandidates(arrayOf(CandidateListItem(comment,"朱逢博"),CandidateListItem(comment,"珠峰")))
            assertEquals(listOf("珠峰"), DecodingInfo.candidates.map { it.text })
        }
    }
}
