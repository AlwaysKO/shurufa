package com.yuyan.imemodule.data.collect

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CandidateReadConcurrencyTest {
    @Test fun `后台写事务尚未结束时按7仍能读取已提交候选学习`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "candidate-concurrency-${UUID.randomUUID()}.db"
        val reader = LocalInputStore(context, name)
        val writer = LocalInputStore(context, name)
        val executor = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            reader.learn("7", "是", pinyin = "shi")
            assertTrue("需要 WAL 隔离后台写入与候选读取", reader.readableDatabase.isWriteAheadLoggingEnabled)
            val writing = executor.submit {
                val db = writer.writableDatabase
                db.beginTransaction()
                try {
                    db.execSQL("UPDATE learned_input SET count=99 WHERE code='7'")
                    ready.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            try {
                val reading = executor.submit<List<LearnedInput>> { reader.learned("7") }
                assertEquals(1L, reading.get(2, TimeUnit.SECONDS).single().count)
            } finally { release.countDown() }
            writing.get(5, TimeUnit.SECONDS)
            assertEquals(99L, reader.learned("7").single().count)
        } finally {
            release.countDown(); executor.shutdown(); executor.awaitTermination(10, TimeUnit.SECONDS)
            writer.close(); reader.close(); context.deleteDatabase(name)
        }
    }
}
