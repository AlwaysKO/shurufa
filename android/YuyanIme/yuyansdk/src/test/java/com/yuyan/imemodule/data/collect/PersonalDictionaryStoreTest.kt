package com.yuyan.imemodule.data.collect
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PersonalDictionaryStoreTest {
    private inline fun <T> LocalInputStore.withStore(block: (LocalInputStore) -> T): T = try { block(this) } finally { close() }
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun store() = LocalInputStore(context,"dictionary-${UUID.randomUUID()}.db",now={1000L})
    private val reading=DictionaryRecord("word","充电宝","","chong dian bao","selection",0,0.0,0,"old")
    private val choice=DictionaryRecord("choice","充电宝","2466434262","","selection",4,4.0,1000,"old")
    private fun snapshot(p:List<DictionaryPolicy> = emptyList()) = DictionarySnapshot("group","a".repeat(64),listOf(reading,choice),p)
    @Test fun `增量习惯跨后台和管理快照只算一次旧版本不回退重启保留`() {
        val name="habits-${UUID.randomUUID()}.db"
        var s=LocalInputStore(context,name,now={1000L})
        val habit=DictionaryHabit(1,choice.copy(version=10))
        s.mergeDictionaryHabits(listOf(habit),"new")
        s.mergeDictionaryHabits(listOf(habit.copy(cursor=9)),"new")
        s.applyDictionarySnapshot(snapshot(),"new")
        assertEquals(4L,s.learned(choice.code).single().count)
        s.mergeDictionaryHabits(listOf(DictionaryHabit(11,choice.copy(count=8,weight=8.0,version=11))),"new")
        s.mergeDictionaryHabits(listOf(habit),"new")
        s.applyDictionarySnapshot(snapshot().copy(entries=emptyList()),"new")
        s.close();s=LocalInputStore(context,name,now={1000L})
        s.withStore {
            assertEquals(8L,it.learned(choice.code).single().count)
            assertEquals(1000L,it.learned(choice.code).single().lastUsed)
            assertTrue(it.dictionaryExport().isEmpty())
            it.applyDictionarySnapshot(snapshot(listOf(DictionaryPolicy(choice.text,"disabled"))),"new")
            it.mergeDictionaryHabits(listOf(habit),"new")
            assertTrue(it.learned(choice.code).isEmpty())
        }
    }
    @Test fun `不同设备证据才能相加较新管理快照覆盖同源加法证据`() {
        store().withStore {s ->
            s.mergeDictionaryHabits(listOf(DictionaryHabit(1,choice.copy(version=2)),DictionaryHabit(2,choice.copy(deviceId="other",version=2))),"new")
            assertEquals(8L,s.learned(choice.code).single().count)
            s.applyDictionarySnapshot(snapshot().copy(entries=listOf(choice.copy(count=6,weight=6.0,version=3))),"new")
            assertEquals(10L,s.learned(choice.code).single().count)
            s.applyDictionarySnapshot(snapshot().copy(entries=emptyList()),"new")
            s.applyDictionarySnapshot(snapshot().copy(entries=listOf(choice.copy(version=2))),"new")
            assertEquals(10L,s.learned(choice.code).single().count)
            assertTrue(s.dictionaryExport().isEmpty())
        }
    }
    @Test fun `管理较新快照先到旧增量后到也不能被空快照回退`() {
        store().withStore {s ->
            s.applyDictionarySnapshot(snapshot().copy(entries=listOf(choice.copy(count=6,weight=6.0,version=3))),"new")
            s.mergeDictionaryHabits(listOf(DictionaryHabit(1,choice.copy(version=2))),"new")
            s.applyDictionarySnapshot(snapshot().copy(entries=emptyList()),"new")
            assertEquals(6L,s.learned(choice.code).single().count)
        }
    }
    @Test fun `自身副本和坏习惯不重复计次不污染数据库`() {
        store().withStore { s ->
            s.learn(choice.code,choice.text)
            s.mergeDictionaryHabits(listOf(DictionaryHabit(1,choice.copy(version=1))),"old")
            assertEquals(1L,s.learned(choice.code).single().count)
            try { s.mergeDictionaryHabits(listOf(DictionaryHabit(1,choice.copy(text="验证码",version=2))),"new");fail() }
            catch(_:IllegalArgumentException) {}
            assertEquals(1L,s.dictionaryExport().single().count)
        }
    }
    @Test fun `换机恢复读音和权重但不再次当成本机上报`() {
        store().withStore { s ->
            s.applyDictionarySnapshot(snapshot(),"new")
            assertEquals("充电宝",s.personalWords("2466434262").single().text)
            assertEquals(4L,s.learned("2466434262").single().count)
            assertEquals(4.0,s.relatedLearned("246643426").single().choice.weight,0.0)
            assertTrue(s.dictionaryExport().isEmpty())
            s.applyDictionarySnapshot(snapshot(),"new")
            assertEquals(4L,s.learned("2466434262").single().count)
        }
    }
    @Test fun `合并本机新增不把自身上报重复恢复`() {
        store().withStore { s ->
            s.learn("2466434262","充电宝",pinyin="chong dian bao")
            s.applyDictionarySnapshot(snapshot(),"new")
            assertEquals(5L,s.learned("2466434262").single().count)
            assertEquals(1L,s.dictionaryExport().single { it.kind=="choice" }.count)
            s.applyDictionarySnapshot(snapshot(),"old")
            assertEquals(1L,s.learned("2466434262").single().count)
        }
    }
    @Test fun `删除阻止重导入复活恢复保留本机真实次数`() {
        store().withStore { s ->
            s.learn("2466434262","充电宝",pinyin="chong dian bao")
            for(status in listOf("disabled","deleted")) {
                s.applyDictionarySnapshot(snapshot(listOf(DictionaryPolicy("充电宝",status))),"new")
                s.rememberWord("充电宝","chong dian bao","system_dictionary")
                s.learn("2466434262","充电宝",pinyin="chong dian bao")
                assertTrue(s.personalWords("2466434262").isEmpty())
                assertTrue(s.learned("2466434262").isEmpty())
                assertTrue(s.relatedLearned("246643426").isEmpty())
            }
            s.applyDictionarySnapshot(snapshot(listOf(DictionaryPolicy("充电宝","enabled"))),"new")
            assertEquals(7L,s.learned("2466434262").single().count)
        }
    }
    @Test fun `旧库升级保留选择不导出敏感词`() {
        val name="dictionary-${UUID.randomUUID()}.db"
        var s=LocalInputStore(context,name)
        s.learn("2466434262","充电宝",pinyin="chong dian bao");s.learn("6462","密码")
        s.writableDatabase.version=4;s.close();s=LocalInputStore(context,name)
        s.withStore {
            assertEquals(1L,it.learned("2466434262").single().count)
            assertEquals(setOf("充电宝"),it.dictionaryExport().map { it.text }.toSet())
        }
    }
    @Test fun `坏快照不能清空旧恢复数据`() {
        store().withStore { s ->
            s.applyDictionarySnapshot(snapshot(),"new")
            try {s.applyDictionarySnapshot(snapshot().copy(entries=listOf(choice.copy(count=-1))),"new");fail("invalid snapshot accepted")}
            catch(_:IllegalArgumentException) {}
            assertEquals(4L,s.learned("2466434262").single().count)
        }
    }
}
