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
class LocalInputStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @Test fun `相关历史保留来源编码且查询不复制计数或污染三码字母码`() {
        val name = "test-${UUID.randomUUID()}.db"
        val store = LocalInputStore(context, name)
        try {
            for (code in listOf("9366", "93663", "936632", "936", "zenme", "987")) store.learn(code, "怎么")
            repeat(2) {
                val records = store.relatedLearned("9366")
                assertEquals(setOf("9366", "93663", "936632"), records.map { it.code }.toSet())
                assertTrue(records.all { it.choice.count == 1L })
                assertEquals(setOf("936"), store.relatedLearned("936").map { it.code }.toSet())
                assertEquals(setOf("zenme"), store.relatedLearned("zenme").map { it.code }.toSet())
            }
            assertEquals(1L, store.learned("93663").single().count)
        } finally {
            store.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun `事件落盘后重启仍然分别等待两个目标确认`() {
        val name = "test-${UUID.randomUUID()}.db"
        var store = LocalInputStore(context, name)
        val event = MobileEvent("event-1", "device-1", "commit", text = "候选词", occurredAt = "2026-09-07T00:00:00Z")
        store.enqueue(event, listOf("http://local", "https://online"))
        store.acknowledge("http://local", listOf(event.id))
        store.close()
        store = LocalInputStore(context, name)
        assertTrue(store.pending("http://local").isEmpty())
        assertEquals(listOf(event), store.pending("https://online"))
        store.acknowledge("https://online", listOf(event.id))
        assertTrue(store.targets().isEmpty())
        store.close()
        context.deleteDatabase(name)
    }
    @Test fun `选词频率重启保留且不同编码互不污染`() {
        val name = "test-${UUID.randomUUID()}.db"
        var store = LocalInputStore(context, name)
        store.learn("46898262", "候选词")
        store.learn("46898262", "候选词")
        store.learn("46898262", "后远啊")
        store.close()
        store = LocalInputStore(context, name)
        assertEquals("候选词", store.learned("46898262").first().text)
        assertEquals(2L, store.learned("46898262").first().count)
        assertTrue(store.learned("64426").isEmpty())
        store.close()
        context.deleteDatabase(name)
    }

    @Test fun `标准拼音学习中文且衰减后追加并重启保留`() {
        val name = "test-${UUID.randomUUID()}.db"
        var now = 1000L
        var store = LocalInputStore(context, name, now = { now })
        repeat(4) { store.learn("xuq", "需求") }
        now += com.yuyan.imemodule.data.completion.PersonalCandidateRanker.HALF_LIFE_MS
        store.learn("xuq", "需求")
        store.learn("xu9", "错误")
        store.learn("xuq", "hello")
        store.close()
        store = LocalInputStore(context, name, now = { now })
        val entry = store.learned("xuq").single()
        assertEquals(5L, entry.count)
        assertEquals(3.0, entry.weight, 0.0001)
        assertEquals(now, entry.lastUsed)
        assertTrue(store.learned("xu9").isEmpty())
        store.close()
        context.deleteDatabase(name)
    }

    @Test fun `版本一升级保留累计历史和待传目标`() {
        val name = "test-${UUID.randomUUID()}.db"
        context.openOrCreateDatabase(name, 0, null).use { db ->
            db.execSQL("CREATE TABLE learned_input (code TEXT NOT NULL,text TEXT NOT NULL,count INTEGER NOT NULL,last_used INTEGER NOT NULL,PRIMARY KEY(code,text))")
            db.execSQL("INSERT INTO learned_input VALUES('987','需求',7,1000)")
            db.execSQL("CREATE TABLE pending_event (id TEXT PRIMARY KEY NOT NULL,payload TEXT NOT NULL)")
            db.execSQL("CREATE TABLE event_target (event_id TEXT NOT NULL,target TEXT NOT NULL,PRIMARY KEY(event_id,target))")
            db.execSQL("INSERT INTO event_target VALUES('event-1','https://online')")
            db.version = 1
        }
        val store = LocalInputStore(context, name)
        try {
            val entry = store.learned("987").single()
            assertEquals(7L, entry.count)
            assertEquals(7.0, entry.weight, 0.0)
            assertEquals(1000L, entry.lastUsed)
            assertEquals(listOf("https://online"), store.targets())
        } finally { store.close() }
        context.deleteDatabase(name)
    }
}
