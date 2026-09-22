package com.yuyan.imemodule.data.collect

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.data.completion.OfflineT9Candidates
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class DictionaryHabitCandidateTest {
    private inline fun <T> LocalInputStore.withStore(block: (LocalInputStore) -> T): T = try {block(this)} finally {close()}
    @Test fun `下载的真实同码习惯改善候选重启仍有效不生成点击`() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val field=OfflineT9Candidates::class.java.getDeclaredField("store").apply {isAccessible=true}
        fun close() { (field.get(OfflineT9Candidates) as? LocalInputStore)?.close();field.set(OfflineT9Candidates,null) }
        close();context.deleteDatabase("local_input.db")
        try {
            OfflineT9Candidates.init(context)
            assertEquals("我们",OfflineT9Candidates.select("966",listOf("我哦","我们"),listOf("wo o","wo men")).firstPage.first().text)
            val now=System.currentTimeMillis()
            LocalInputStore(context).withStore {store ->
                store.mergeDictionaryHabits(listOf(DictionaryHabit(1,DictionaryRecord("choice","我哦","966","","selection",3,3.0,now,"old",1))),"new")
            }
            repeat(2) {
                OfflineT9Candidates.init(context)
                assertEquals("我哦",OfflineT9Candidates.select("966",listOf("我哦","我们"),listOf("wo o","wo men")).firstPage.first().text)
                LocalInputStore(context).withStore {assertTrue(it.dictionaryExport().isEmpty());assertEquals(3L,it.learned("966").single().count)}
                close()
            }
        } finally {close();context.deleteDatabase("local_input.db")}
    }
}
