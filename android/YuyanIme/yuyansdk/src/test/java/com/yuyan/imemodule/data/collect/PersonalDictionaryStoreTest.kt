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
