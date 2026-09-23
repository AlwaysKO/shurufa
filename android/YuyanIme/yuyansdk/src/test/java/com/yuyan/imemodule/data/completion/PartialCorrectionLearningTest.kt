package com.yuyan.imemodule.data.completion

import org.junit.Assert.*
import org.junit.Test

class PartialCorrectionLearningTest {
    private fun commit(t: CorrectionLearningTracker, s: T9CommitSelection, before: String?, after: String?, at: Long, reward: String = "new"): Any? {
        val m = CorrectionLearningTracker::class.java.getDeclaredMethod("commitSelection", T9CommitSelection::class.java,
            String::class.java, String::class.java, String::class.java, Long::class.javaPrimitiveType)
        return m.invoke(t, s, before, after, reward, at)
    }
    private fun field(result: Any, name: String): Any? = result.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(result)
    private val original = T9CommitSelection("526744", "老是", "lao shi",
        listOf(T9SelectedPart("老", "lao"), T9SelectedPart("是", "shi")))
    private fun start(s: T9CommitSelection = original) = CorrectionLearningTracker().apply {
        commit(this, s, "前后", "前${s.text}后", 1000, "old")
    }
    @Test fun `原位同音尾部纠正撤销错误整词且只保留明确选择的前段`() {
        val t = start()
        t.delete("前老是后", "前老后", 2000)
        val result = commit(t, T9CommitSelection("744", "师", "shi"), "前老后", "前老师后", 3000)!!
        assertEquals("old", field(result, "rewardId"))
        assertEquals(listOf(T9SelectedPart("老", "lao")), field(result, "retainedParts"))
        assertNull(commit(t, T9CommitSelection("744", "师", "shi"), "前老后", "前老师后", 3001))
    }
    @Test fun `整词一次选中不凭保留子串制造独立点击`() {
        val t = start(original.copy(parts = emptyList()))
        t.delete("前老是后", "前老后", 2000)
        val result = commit(t, T9CommitSelection("744", "师", "shi"), "前老后", "前老师后", 3000)!!
        assertEquals(emptyList<T9SelectedPart>(), field(result, "retainedParts"))
    }
    @Test fun `同音尾部允许可信简拼重输但不是同数字就认为同音`() {
        val t = start()
        t.delete("前老是后", "前老后", 2000)
        assertNotNull(commit(t, T9CommitSelection("7", "师", "shi"), "前老后", "前老师后", 3000))
        val other = start()
        other.delete("前老是后", "前老后", 2000)
        assertNull(commit(other, T9CommitSelection("744", "日", "ri"), "前老后", "前老日后", 3000))
    }
    @Test fun `异音改写相同文字缺失读音错码及跨位置均不撤销`() {
        for (kind in listOf("reading", "same", "missing", "code", "position", "old-reading", "old-code", "late")) {
            val t = start(when (kind) {
                "old-reading" -> original.copy(pinyin = "")
                "old-code" -> original.copy(code = "222222")
                else -> original
            })
            t.delete("前老是后", "前老后", 2000)
            val s = when (kind) {
                "reading" -> T9CommitSelection("226", "板", "ban")
                "same" -> T9CommitSelection("744", "是", "shi")
                "missing" -> T9CommitSelection("744", "师")
                "code" -> T9CommitSelection("222", "师", "shi")
                else -> T9CommitSelection("744", "师", "shi")
            }
            assertNull(kind, commit(t, s, "前老后", if (kind == "position") "前师老后" else "前老${s.text}后", if (kind == "late") 17001 else 3000))
        }
    }
    @Test fun `删除超出本词和快照断裂不关联之前选择`() {
        for (after in listOf("前后", "前", "前老师后")) {
            val t = start()
            t.delete("不匹配", after, 2000)
            assertNull(commit(t, T9CommitSelection("744", "师", "shi"), after, after + "师", 3000))
        }
    }
    @Test fun `保留段必须来自完整一致的原分段证据`() {
        val t = start(original.copy(parts = listOf(T9SelectedPart("老", "lao"), T9SelectedPart("师", "shi"))))
        t.delete("前老是后", "前老后", 2000)
        val result = commit(t, T9CommitSelection("744", "师", "shi"), "前老后", "前老师后", 3000)!!
        assertEquals(emptyList<T9SelectedPart>(), field(result, "retainedParts"))
    }
    @Test fun `跨分段删除只保留未被删到的完整前段`() {
        val original = T9CommitSelection("943633539462", "真的可以吗", "zhen de ke yi ma",
            listOf(T9SelectedPart("真的", "zhen de"), T9SelectedPart("可以", "ke yi"), T9SelectedPart("吗", "ma")))
        val t = start(original)
        t.delete("前真的可以吗后", "前真的可后", 2000)
        val result = commit(t, T9CommitSelection("9462", "以嘛", "yi ma"), "前真的可后", "前真的可以嘛后", 3000)!!
        assertEquals(listOf(T9SelectedPart("真的", "zhen de")), field(result, "retainedParts"))
    }
    @Test fun `完整多字尾段同音替换有明确读音才撤销`() {
        val t = start(T9CommitSelection("966243", "我那个", "wo na ge", listOf(T9SelectedPart("我", "wo"), T9SelectedPart("那个", "na ge"))))
        t.delete("前我那个后", "前我后", 2000)
        val result = commit(t, T9CommitSelection("6243", "哪个", "na ge"), "前我后", "前我哪个后", 3000)!!
        assertEquals(listOf(T9SelectedPart("我", "wo")), field(result, "retainedParts"))
    }

}
