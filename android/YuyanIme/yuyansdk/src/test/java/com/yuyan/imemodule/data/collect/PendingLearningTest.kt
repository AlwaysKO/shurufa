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
            db.learn("326", "房", pinyin = "fang")
            db.stageLearning("receipt", listOf(PendingChoice("3264542", "房价", "fang jia"),
                PendingChoice("3264", "房", "fang"), PendingChoice("542", "价", "jia")), emptyList())
            assertTrue(db.cancelLearning("receipt"))
            assertTrue(db.learned("3264542").isEmpty())
            assertEquals(1L, db.learned("326").single().count)
            assertTrue(db.personalWords("3264").any { it.text == "房" })
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
