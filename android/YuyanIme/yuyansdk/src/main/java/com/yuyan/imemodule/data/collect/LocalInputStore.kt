package com.yuyan.imemodule.data.collect

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import com.yuyan.imemodule.data.completion.PersonalCandidateRanker

internal data class PendingReport(val id: String, val kind: String, val payload: String)

internal data class LearnedInput(val text: String, val count: Long, val weight: Double, val lastUsed: Long)
internal data class CodedLearnedInput(val code: String, val choice: LearnedInput)

/** 独立数据库，不迁移或清空既有 Rime 用户库和剪贴板库。 */
internal class LocalInputStore(context: Context, name: String = "local_input.db", private val now: () -> Long = System::currentTimeMillis) :
    SQLiteOpenHelper(context.applicationContext, name, null, 3) {
    private val json = Json { ignoreUnknownKeys = true }
    override fun onCreate(db: SQLiteDatabase) {
        createReportTables(db)
        db.execSQL("CREATE TABLE pending_event (id TEXT PRIMARY KEY NOT NULL, payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE event_target (event_id TEXT NOT NULL, target TEXT NOT NULL, PRIMARY KEY(event_id,target))")
        db.execSQL("CREATE INDEX event_target_url ON event_target(target)")
        db.execSQL("CREATE TABLE learned_input (code TEXT NOT NULL, text TEXT NOT NULL, count INTEGER NOT NULL, last_used INTEGER NOT NULL, weight REAL NOT NULL DEFAULT 0, PRIMARY KEY(code,text))")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 3) createReportTables(db)
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE learned_input ADD COLUMN weight REAL NOT NULL DEFAULT 0")
            db.execSQL("UPDATE learned_input SET weight=count")
        }
    }

    @Synchronized fun enqueue(event: MobileEvent, targets: List<String>) {
        require(targets.isNotEmpty())
        val db = writableDatabase
        db.beginTransaction()
        try {
            val inserted = db.insertWithOnConflict("pending_event", null, ContentValues().apply {
                put("id", event.id); put("payload", json.encodeToString(MobileEvent.serializer(), event))
            }, SQLiteDatabase.CONFLICT_IGNORE)
            // 重复入队不能复活已经确认的目标。
            if (inserted != -1L) targets.distinct().forEach { target ->
                db.execSQL("INSERT INTO event_target(event_id,target) VALUES(?,?)", arrayOf(event.id, target))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized fun targets(): List<String> = readableDatabase.rawQuery("SELECT DISTINCT target FROM event_target", null).use { c ->
        buildList { while (c.moveToNext()) add(c.getString(0)) }
    }

    @Synchronized fun pending(target: String, limit: Int = 500): List<MobileEvent> = readableDatabase.rawQuery(
        "SELECT e.payload FROM pending_event e JOIN event_target t ON e.id=t.event_id WHERE t.target=? ORDER BY e.rowid LIMIT ?",
        arrayOf(target, limit.coerceIn(1, 500).toString()),
    ).use { c -> buildList { while (c.moveToNext()) add(json.decodeFromString(MobileEvent.serializer(), c.getString(0))) } }

    @Synchronized fun acknowledge(target: String, ids: List<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            ids.forEach { id -> db.delete("event_target", "event_id=? AND target=?", arrayOf(id, target)) }
            db.execSQL("DELETE FROM pending_event WHERE NOT EXISTS(SELECT 1 FROM event_target t WHERE t.event_id=pending_event.id)")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun createReportTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE pending_report (id TEXT PRIMARY KEY NOT NULL, kind TEXT NOT NULL, payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE report_target (report_id TEXT NOT NULL, target TEXT NOT NULL, attempted_at INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(report_id,target))")
        db.execSQL("CREATE INDEX report_target_url ON report_target(target,attempted_at)")
    }

    @Synchronized fun enqueueReport(report: PendingReport, targets: List<String>) {
        require(targets.isNotEmpty())
        val db = writableDatabase
        db.beginTransaction()
        try {
            val inserted = db.insertWithOnConflict("pending_report", null, ContentValues().apply {
                put("id", report.id); put("kind", report.kind); put("payload", report.payload)
            }, SQLiteDatabase.CONFLICT_IGNORE)
            if (inserted != -1L) targets.distinct().forEach { target ->
                db.execSQL("INSERT INTO report_target(report_id,target) VALUES(?,?)", arrayOf(report.id, target))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized fun reportTargets(): List<String> = readableDatabase.rawQuery("SELECT DISTINCT target FROM report_target", null).use { c ->
        buildList { while(c.moveToNext()) add(c.getString(0)) }
    }
    @Synchronized fun pendingReports(target: String, limit: Int = 20, includeLocation: Boolean = true): List<PendingReport> = readableDatabase.rawQuery(
        "SELECT r.id,r.kind,LENGTH(r.payload) FROM pending_report r JOIN report_target t ON t.report_id=r.id WHERE t.target=? AND (? = '1' OR r.kind != 'location') ORDER BY CASE r.kind WHEN 'chat_asset' THEN 0 WHEN 'chat_messages' THEN 1 ELSE 2 END,t.attempted_at,r.rowid LIMIT ?",
        arrayOf(target, if(includeLocation) "1" else "0", limit.coerceIn(1,20).toString()),
    ).use { c -> buildList {
        var characters = 0
        while(c.moveToNext()) {
            val length = c.getInt(2)
            if (isNotEmpty() && characters + length > 262_144) break
            val id = c.getString(0)
            add(PendingReport(id,c.getString(1),readReportPayload(id,length)))
            characters += length
        }
    } }

    // CursorWindow cannot hold a multi-megabyte Base64 row. Read bounded SQLite text slices.
    private fun readReportPayload(id: String, length: Int): String = buildString {
        var start = 1
        while (start <= length) {
            readableDatabase.rawQuery("SELECT SUBSTR(payload,?,65536) FROM pending_report WHERE id=?", arrayOf(start.toString(),id)).use { c ->
                check(c.moveToFirst()) { "Missing durable report" }
                append(c.getString(0))
            }
            start += 65536
        }
    }
    @Synchronized fun deferReport(target: String, id: String) {
        writableDatabase.execSQL("UPDATE report_target SET attempted_at=? WHERE report_id=? AND target=?", arrayOf<Any>(now(),id,target))
    }
    @Synchronized fun acknowledgeReports(target: String, ids: List<String>) {
        val db=writableDatabase
        db.beginTransaction()
        try {
            ids.forEach { db.delete("report_target","report_id=? AND target=?",arrayOf(it,target)) }
            db.execSQL("DELETE FROM pending_report WHERE NOT EXISTS(SELECT 1 FROM report_target t WHERE t.report_id=pending_report.id)")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized fun learn(code: String, text: String, targets: List<String> = emptyList()) {
        if (!validCode(code) || text.length !in 1..30 || text.any { it !in '\u4e00'..'\u9fff' }) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val timestamp = now()
            val previous = db.rawQuery("SELECT weight,last_used FROM learned_input WHERE code=? AND text=?", arrayOf(code, text)).use { c ->
                if (c.moveToFirst()) PersonalCandidateRanker.decay(c.getDouble(0), c.getLong(1), timestamp) else 0.0
            }
            db.execSQL("INSERT OR IGNORE INTO learned_input(code,text,count,last_used) VALUES(?,?,0,0)", arrayOf(code, text))
            db.execSQL("UPDATE learned_input SET count=count+1,last_used=?,weight=? WHERE code=? AND text=?", arrayOf<Any>(timestamp, previous + 1.0, code, text))
            if (targets.isNotEmpty()) {
                val count = db.rawQuery("SELECT count FROM learned_input WHERE code=? AND text=?", arrayOf(code,text)).use { c -> c.moveToFirst(); c.getLong(0) }
                val payload = buildJsonObject {
                    put("code",code); put("text",text); put("count",count); put("weight",previous+1.0); put("last_used",timestamp)
                }.toString()
                enqueueReport(PendingReport(java.util.UUID.randomUUID().toString(),"personal_choice",payload),targets)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    @Synchronized fun learned(code: String): List<LearnedInput> = readableDatabase.rawQuery(
        "SELECT text,count,weight,last_used FROM learned_input WHERE code=? ORDER BY last_used DESC,text", arrayOf(code),
    ).use { c -> buildList { while (c.moveToNext()) add(LearnedInput(c.getString(0), c.getLong(1), c.getDouble(2), c.getLong(3))) } }
        .sortedByDescending { PersonalCandidateRanker.decay(it.weight, it.lastUsed, now()) }.take(64)

    /** 只读潜在相关编码，调用方还必须按候选实际读音校验；不复制次数或上传事件。 */
    @Synchronized fun relatedLearned(code: String): List<CodedLearnedInput> {
        if (code.length !in 4..30 || code.any { it !in '2'..'9' }) {
            return learned(code).map { CodedLearnedInput(code, it) }
        }
        val prefixes = (4..code.length).map { code.take(it) }
        val placeholders = prefixes.joinToString(",") { "?" }
        return readableDatabase.rawQuery(
            "SELECT code,text,count,weight,last_used FROM learned_input WHERE code GLOB ? OR code IN ($placeholders)",
            (listOf("$code*") + prefixes).toTypedArray(),
        ).use { c -> buildList {
            while (c.moveToNext()) add(CodedLearnedInput(c.getString(0),
                LearnedInput(c.getString(1), c.getLong(2), c.getDouble(3), c.getLong(4))))
        } }
    }

    private fun validCode(code: String): Boolean =
        (code.length in 3..30 && code.all { it in '2'..'9' }) ||
            (code.length in 2..30 && code.all { it in 'a'..'z' })
}
