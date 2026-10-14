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
class LockedPinyinCandidatesTest {
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

    @Test fun `锁定nan和s后未学习南山能补全且不混入异读整词`() {
        val result = OfflineT9Candidates.select("nan's", listOf("那你", "拿捏", "难受", "男生"),
            listOf("na ni", "na nie", "nan shou", "nan sheng"))
        assertTrue("锁音仍须补充本地末音节候选", result.firstPage.take(5).any { it.text == "南山" })
        assertFalse(result.firstPage.any { it.text in setOf("那你", "拿捏", "男人") })
        assertEquals(2, result.firstPage.first { it.text == "难受" }.nativeIndex)
        assertNull(result.firstPage.first { it.text == "南山" }.nativeIndex)
    }

    @Test fun `锁音分页继续过滤不符音节且保留原生位置`() {
        val result = OfflineT9Candidates.select("nan's", listOf("难受"), listOf("nan shou"))
        val size = result.firstPage.size
        assertEquals(listOf(1), result.appendNativePage(listOf("拿捏", "南宋", "男人"), "",
            listOf("na nie", "nan song", "nan ren")))
        assertEquals(2, result.at(size)?.nativeIndex)
    }

    @Test fun `锁音约束保留合法分段单字并拒绝不同首音节`() {
        val result = OfflineT9Candidates.select("nan's", listOf("那", "难", "男"), listOf("na", "nan", "nan"))
        assertFalse(result.firstPage.any { it.text == "那" })
        assertTrue(result.firstPage.any { it.text == "难" && it.nativeIndex == 1 })
        assertTrue(result.firstPage.any { it.text == "男" && it.nativeIndex == 2 })
    }

    @Test fun `锁音补全适用于不同词且不扩写已完整锁定的前音节`() {
        val result = OfflineT9Candidates.select("bei'j")
        assertTrue(result.firstPage.any { it.text == "北京" })
        assertFalse(result.firstPage.any { it.pinyin.startsWith("ben ") })
        assertTrue(OfflineT9Candidates.select("nan'7").firstPage.any { it.text == "南山" })
        assertFalse(OfflineT9Candidates.select("nan's").firstPage.any { it.text == "南区" })
    }

    @Test fun `未锁音同码补全按公共词频混排而非原生来源抢占首屏`() {
        val result = OfflineT9Candidates.select("6267", listOf("男人", "难受", "冒泡", "男生", "南区"),
            listOf("nan ren", "nan shou", "mao pao", "nan sheng", "nan qu")).firstPage
        assertTrue(result.map { it.text }.toString(), result.take(5).any { it.text == "南山" })
        assertTrue(result.indexOfFirst { it.text == "南山" } < result.indexOfFirst { it.text == "冒泡" })
        assertTrue(result.indexOfFirst { it.text == "男生" } < result.indexOfFirst { it.text == "难受" })
        assertEquals(3, result.first { it.text == "男生" }.nativeIndex)
    }
    @Test fun `一键声母锁定也必须过滤不同读音`() {
        val result = OfflineT9Candidates.selectLocked("n'", listOf("你", "好", "呢"), listOf("ni", "hao", "ne"))
        assertEquals(listOf("你", "呢"), result.firstPage.map { it.text })
        assertEquals(listOf(0, 2), result.firstPage.map { it.nativeIndex })
    }
    @Test fun `原生拼音用单引号分隔时仍按同一公共词频混排`() {
        val result = OfflineT9Candidates.select("6267", listOf("男人", "难受", "冒泡", "男生", "南区"),
            listOf("nan'ren", "nan'shou", "mao'pao", "nan'sheng", "nan'qu")).firstPage
        assertTrue(result.map { it.text }.toString(),
            result.indexOfFirst { it.text == "南山" } < result.indexOfFirst { it.text == "冒泡" })
        assertEquals(3, result.first { it.text == "男生" }.nativeIndex)
    }
}
