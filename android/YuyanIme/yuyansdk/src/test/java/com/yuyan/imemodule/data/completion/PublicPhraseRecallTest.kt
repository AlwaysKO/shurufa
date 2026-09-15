package com.yuyan.imemodule.data.completion

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import com.yuyan.imemodule.data.collect.LocalInputStore
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PublicPhraseRecallTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private fun clearStore() {
        val field=OfflineT9Candidates::class.java.getDeclaredField("store").apply { isAccessible=true }
        (field.get(OfflineT9Candidates) as? LocalInputStore)?.close();field.set(OfflineT9Candidates,null)
        context.deleteDatabase("local_input.db")
    }
    @Before fun before() { clearStore() }
    @After fun after() { clearStore() }

    @Test fun `公开原词你赢了不会被小词库误删且学习覆盖两种输入`() {
        OfflineT9Candidates.init(context)
        val words=listOf("你醒了", "你赢了", "逆行")
        val readings=listOf("ni xing le", "ni ying le", "ni xing")
        for(code in listOf("6494645", "64946453")) {
            val result=OfflineT9Candidates.select(code,words,readings)
            assertTrue(code,result.firstPage.any { it.text=="你赢了" && it.nativeIndex==1 })
            assertTrue(result.firstPage.any { it.text=="你醒了" })
            assertEquals(listOf(0),result.appendNativePage(listOf("你赢了"),code,listOf("ni ying le")))
        }
        repeat(3) { OfflineT9Candidates.learn("64946453", "你赢了", "ni ying le") }
        // 关闭数据库后重开，不能仅验证同一进程内存里的排序。
        val field=OfflineT9Candidates::class.java.getDeclaredField("store").apply { isAccessible=true }
        (field.get(OfflineT9Candidates) as? LocalInputStore)?.close();field.set(OfflineT9Candidates,null)
        OfflineT9Candidates.init(context)
        for(code in listOf("6494645", "64946453")) {
            assertEquals("你赢了",OfflineT9Candidates.select(code,words,readings).firstPage.first().text)
        }
    }
    @Test fun `索引缺失安全回退原有词典`() {
        OfflineT9Candidates.init(context)
        val indexField=OfflineT9Candidates::class.java.getDeclaredField("publicPhrases").apply { isAccessible=true }
        val attemptedField=OfflineT9Candidates::class.java.getDeclaredField("publicPhrasesAttempted").apply { isAccessible=true }
        val index=indexField.get(OfflineT9Candidates)
        val attempted=attemptedField.getBoolean(OfflineT9Candidates)
        try {
            indexField.set(OfflineT9Candidates,null);attemptedField.setBoolean(OfflineT9Candidates,false)
            val missingAssets=object : android.content.ContextWrapper(context) {
                override fun getAssets(): android.content.res.AssetManager = android.content.res.Resources.getSystem().assets
            }
            OfflineT9Candidates.init(missingAssets)
            assertNull(indexField.get(OfflineT9Candidates))
            assertEquals("充电宝",OfflineT9Candidates.select("24664342622",listOf("充电"),listOf("chong dian")).firstPage.first().text)
        } finally {
            indexField.set(OfflineT9Candidates,index);attemptedField.setBoolean(OfflineT9Candidates,attempted)
        }
    }

}
