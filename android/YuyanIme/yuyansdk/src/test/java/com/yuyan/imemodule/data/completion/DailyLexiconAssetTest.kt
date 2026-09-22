package com.yuyan.imemodule.data.completion

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.data.collect.LocalInputStore
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DailyLexiconAssetTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun reset() {
        val field = OfflineT9Candidates::class.java.getDeclaredField("store").apply { isAccessible = true }
        (field.get(OfflineT9Candidates) as? LocalInputStore)?.close()
        field.set(OfflineT9Candidates, null)
        for (name in listOf("lexicon", "domains")) {
            OfflineT9Candidates::class.java.getDeclaredField(name).apply { isAccessible = true }.set(OfflineT9Candidates, null)
        }
        context.deleteDatabase("local_input.db")
    }
    @Before fun setup() { reset(); OfflineT9Candidates.init(context) }
    @After fun cleanup() { reset() }

    @Test fun `新增日常词在无网络无学习记录时进入实际可选候选`() {
        val samples = listOf("买菜" to "mai cai", "洗碗" to "xi wan", "多少钱" to "duo shao qian",
            "收货" to "shou huo", "到家" to "dao jia", "奶茶" to "nai cha", "谢谢" to "xie xie",
            "快递" to "kuai di", "地铁" to "di tie", "下班" to "xia ban", "银行" to "yin hang")
        for ((text, pinyin) in samples) {
            val code = T9Lexicon.digits(pinyin.replace(" ", ""))
            val results = OfflineT9Candidates.select(code, emptyList(), emptyList()).firstPage
            assertTrue("$text/$code: $results", results.any { it.text == text && it.pinyin == pinyin })
            if (text != "洗碗") assertTrue("$text 前五: ${results.take(5)}", results.take(5).any { it.text == text })
        }
        val db = LocalInputStore(context)
        try { assertTrue("公共词频不能成为个人点击或词条", db.dictionaryExport().isEmpty()) }
        finally { db.close() }
    }

    @Test fun `公共词库更新不压过用户真实改选且不放宽长词简拼`() {
        assertEquals("我们", OfflineT9Candidates.select("966", listOf("我哦"), listOf("wo o")).firstPage.first().text)
        OfflineT9Candidates.learn("966", "我哦", "wo o")
        assertEquals("我哦", OfflineT9Candidates.select("966", listOf("我哦"), listOf("wo o")).firstPage.first().text)
        assertFalse(OfflineT9Candidates.select("649439", emptyList(), emptyList()).firstPage.any { it.text == "美国最高法院" })
    }
}
