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
class PendingLearningTest {
    @Test fun `临时奖励本机可见但未上传取消只影响本笔`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "pending-${UUID.randomUUID()}.db"
        var now = 1000L
        val db = LocalInputStore(context, name, now = { now })
        try {
            db.learn("3264542", "房价")
            val old = db.dictionaryExport().single()
            now = 2000
            db.stageLearning("receipt", listOf(PendingChoice("3264542", "房价", "fang jia")), listOf("https://example.test"))
            assertEquals(2L, db.learned("3264542").single().count)
            assertEquals(listOf(old), db.dictionaryExport())
            assertTrue(db.pendingReports("https://example.test").isEmpty())
            assertTrue(db.cancelLearning("receipt"))
            assertFalse(db.cancelLearning("receipt"))
            assertEquals(listOf(old), db.dictionaryExport())
            assertEquals(1L, db.learned("3264542").single().count)
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun `到期事务确认重启恢复且重复结算不会增加点击`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "pending-${UUID.randomUUID()}.db"
        var now = 1000L
        var db = LocalInputStore(context, name, now = { now })
        try {
            val choice = listOf(PendingChoice("3", "的", "de"))
            db.stageLearning("receipt", choice, listOf("https://example.test"))
            db.stageLearning("receipt", choice, listOf("https://example.test"))
            assertEquals(1L, db.learned("3").single().count)
            now = 18000
            db.settleLearning()
            assertTrue(db.dictionaryExport().isEmpty())
            db.close()
            db = LocalInputStore(context, name, now = { now })
            now++
            db.settleLearning()
            db.settleLearning()
            assertFalse(db.cancelLearning("receipt"))
            assertEquals(1L, db.dictionaryExport().first { it.kind == "choice" }.count)
            assertEquals(1000L, db.dictionaryExport().first { it.kind == "choice" }.lastUsed)
            assertEquals(1, db.pendingReports("https://example.test").size)
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun `完整纠错取消整词及分段奖励不删除旧词条`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "pending-${UUID.randomUUID()}.db"
        val db = LocalInputStore(context, name, now = { 1000 })
        try {
            db.learn("3264", "房", pinyin = "fang")
            db.stageLearning("receipt", listOf(PendingChoice("3264542", "房价", "fang jia"),
                PendingChoice("3264", "房", "fang"), PendingChoice("542", "价", "jia")), emptyList())
            assertTrue(db.cancelLearning("receipt"))
            assertTrue(db.learned("3264542").isEmpty())
            assertEquals(1L, db.learned("3264").single().count)
            assertTrue(db.personalWords("3264").any { it.text == "房" })
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun `结算失败整体回滚且另一存储实例重试只入队一次`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "pending-${UUID.randomUUID()}.db"
        var now = 1000L
        val db = LocalInputStore(context, name, now = { now })
        val other = LocalInputStore(context, name, now = { now })
        try {
            db.stageLearning("receipt", listOf(PendingChoice("3", "的")), listOf("https://example.test"))
            db.writableDatabase.execSQL("CREATE TRIGGER fail_report BEFORE INSERT ON pending_report BEGIN SELECT RAISE(ABORT, 'test failure'); END")
            now = 18001
            assertTrue(runCatching { other.settleLearning() }.isFailure)
            db.readableDatabase.rawQuery("SELECT COUNT(*) FROM learned_input", null).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            db.readableDatabase.rawQuery("SELECT COUNT(*) FROM pending_learning", null).use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
            db.writableDatabase.execSQL("DROP TRIGGER fail_report")
            other.settleLearning(); db.settleLearning()
            assertEquals(1L, db.learned("3").single().count)
            assertEquals(1, db.pendingReports("https://example.test").size)
        } finally { other.close(); db.close(); context.deleteDatabase(name) }
    }

    @Test fun `延迟确认不覆盖较新的正式选择时间也不增加第二次奖励`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "pending-${UUID.randomUUID()}.db"
        var now = 1000L
        val db = LocalInputStore(context, name, now = { now })
        try {
            db.stageLearning("receipt", listOf(PendingChoice("3", "的")), emptyList())
            now = 2000; db.learn("3", "的")
            now = 18001; db.settleLearning()
            assertEquals(2L, db.learned("3").single().count)
            assertEquals(2000L, db.dictionaryExport().single().lastUsed)
            assertTrue(db.dictionaryExport().single().weight <= 2.0)
        } finally { db.close(); context.deleteDatabase(name) }
    }

}
