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
    private val sevenDays = 7L * 24 * 60 * 60 * 1000

    @Test fun `聊天报告线上确认后本地失败仅保留七天`() {
        val name = "test-${UUID.randomUUID()}.db"
        var now = 1_000L
        val store = LocalInputStore(context, name, now = { now })
        try {
            val report = PendingReport("chat-asset-1", "chat_asset", "{}")
            store.enqueueReport(report, listOf("http://local", "https://online"))
            store.acknowledgeReports("https://online", listOf(report.id), "https://online")

            now += sevenDays - 1
            store.pruneExpiredLocalChatReports("https://online", sevenDays)
            assertEquals(listOf(report.id), store.pendingReports("http://local").map { it.id })

            now += 1
            store.pruneExpiredLocalChatReports("https://online", sevenDays)
            assertTrue(store.pendingReports("http://local").isEmpty())
            assertTrue(store.reportTargets().isEmpty())
        } finally { store.close(); context.deleteDatabase(name) }
    }

    @Test fun `聊天报告线上未确认无论多久都不能删除`() {
        val name = "test-${UUID.randomUUID()}.db"
        var now = 1_000L
        val store = LocalInputStore(context, name, now = { now })
        try {
            val report = PendingReport("chat-message-1", "chat_messages", "{}")
            store.enqueueReport(report, listOf("http://local", "https://online"))
            now += sevenDays * 10
            store.pruneExpiredLocalChatReports("https://online", sevenDays)
            assertEquals(listOf(report.id), store.pendingReports("http://local").map { it.id })
            assertEquals(listOf(report.id), store.pendingReports("https://online").map { it.id })
        } finally { store.close(); context.deleteDatabase(name) }
    }

    @Test fun `聊天报告两端确认后立即删除手机记录`() {
        val name = "test-${UUID.randomUUID()}.db"
        val store = LocalInputStore(context, name, now = { 1_000L })
        try {
            val report = PendingReport("chat-message-2", "chat_messages", "{}")
            store.enqueueReport(report, listOf("http://local", "https://online"))
            store.acknowledgeReports("https://online", listOf(report.id), "https://online")
            store.acknowledgeReports("http://local", listOf(report.id), "https://online")
            assertTrue(store.reportTargets().isEmpty())
            assertTrue(store.pendingReports("http://local").isEmpty())
            assertTrue(store.pendingReports("https://online").isEmpty())
        } finally { store.close(); context.deleteDatabase(name) }
    }

    @Test fun `线上域名变化只迁移未确认线上目标并保留电脑目标`() {
        val name = "test-${UUID.randomUUID()}.db"
        val store = LocalInputStore(context, name, now = { 1_000L })
        try {
            val event = MobileEvent("event-domain", "device-1", "commit", text = "内容", occurredAt = "2026-09-16T00:00:00Z")
            val report = PendingReport("chat-domain", "chat_asset", "{}")
            store.enqueue(event, listOf("http://local", "https://old.example"))
            store.enqueueReport(report, listOf("http://local", "https://old.example"))

            store.replaceTarget("https://old.example", "https://new.example")

            assertTrue(store.pending("https://old.example").isEmpty())
            assertTrue(store.pendingReports("https://old.example").isEmpty())
            assertEquals(listOf(event), store.pending("https://new.example"))
            assertEquals(listOf(report.id), store.pendingReports("https://new.example").map { it.id })
            assertEquals(listOf(event), store.pending("http://local"))
            assertEquals(listOf(report.id), store.pendingReports("http://local").map { it.id })
        } finally { store.close(); context.deleteDatabase(name) }
    }

    @Test fun `当前版本不能根据线上URL缺失推断成功并删除`() {
        val name = "test-${UUID.randomUUID()}.db"
        var now = 1_000L
        val store = LocalInputStore(context, name, now = { now })
        try {
            val report = PendingReport("legacy-chat-1", "chat_asset", "{}")
            store.enqueueReport(report, listOf("http://local", "https://online"))
            store.acknowledgeReports("https://online", listOf(report.id)) // 旧版没有保存线上确认时间
            now += sevenDays * 10
            store.pruneExpiredLocalChatReports("https://online", sevenDays)
            assertEquals(listOf(report.id), store.pendingReports("http://local").map { it.id })
        } finally { store.close(); context.deleteDatabase(name) }
    }

    @Test fun `版本五升级保留聊天报告并增加线上确认状态`() {
        val name = "test-${UUID.randomUUID()}.db"
        context.openOrCreateDatabase(name, 0, null).use { db ->
            db.execSQL("CREATE TABLE pending_report (id TEXT PRIMARY KEY NOT NULL,kind TEXT NOT NULL,payload TEXT NOT NULL)")
            db.execSQL("CREATE TABLE report_target (report_id TEXT NOT NULL,target TEXT NOT NULL,attempted_at INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(report_id,target))")
            db.execSQL("INSERT INTO pending_report VALUES('old-chat','chat_messages','{}')")
            db.execSQL("INSERT INTO report_target VALUES('old-chat','http://local',0)")
            db.version = 5
        }
        val store = LocalInputStore(context, name, now = { 1_000L })
        try {
            assertEquals("old-chat", store.pendingReports("http://local").single().id)
            store.pruneExpiredLocalChatReports("https://online", 0)
            assertTrue(store.pendingReports("http://local").isEmpty())
        } finally { store.close(); context.deleteDatabase(name) }
    }
    @Test fun `版本三升级新增个人读音表但不改旧次数权重和待上传报告`() {
        val name = "test-${UUID.randomUUID()}.db"
        val old = LocalInputStore(context, name)
        old.learn("94363362", "真的吗")
        old.enqueueReport(PendingReport("old-report", "test", "{}"), listOf("https://online"))
        old.writableDatabase.execSQL("DROP TABLE personal_word")
        old.writableDatabase.version = 3
        old.close()
        val store = LocalInputStore(context, name)
        try {
            assertEquals(1L, store.learned("94363362").single().count)
            assertEquals(1.0, store.learned("94363362").single().weight, 0.0)
            assertEquals("old-report", store.pendingReports("https://online").single().id)
            store.rememberWord("真的吗", "zhen de ma", "system_dictionary")
            assertEquals("真的吗", store.personalWords("9436336").single().text)
            assertEquals(1L, store.learned("94363362").single().count)
        } finally { store.close(); context.deleteDatabase(name) }
    }

    @Test fun `不符合实际码或逐字音节数的读音不建立独立召回`() {
        val name = "test-${UUID.randomUUID()}.db"
        val store = LocalInputStore(context, name)
        try {
            store.learn("94363362", "真的吗", pinyin = "zhen ma")
            store.learn("94363362", "真的吗", pinyin = "ni hao ma")
            assertTrue(store.personalWords("94363362").isEmpty())
            assertTrue(store.personalWords("6442662").isEmpty())
            assertEquals(2L, store.learned("94363362").single().count)
        } finally { store.close(); context.deleteDatabase(name) }
    }

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
