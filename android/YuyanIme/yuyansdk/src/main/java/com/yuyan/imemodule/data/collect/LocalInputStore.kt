package com.yuyan.imemodule.data.collect

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import com.yuyan.imemodule.data.completion.CorrectionLearningTracker
import com.yuyan.imemodule.data.completion.PersonalCandidateRanker
import com.yuyan.imemodule.data.completion.PersonalWordReading
import com.yuyan.imemodule.data.completion.T9Candidate
import com.yuyan.imemodule.data.completion.T9Lexicon

@Serializable internal data class PendingChoice(val code: String, val text: String, val pinyin: String = "")

internal data class PendingReport(val id: String, val kind: String, val payload: String)

internal data class LearnedInput(val text: String, val count: Long, val weight: Double, val lastUsed: Long)
internal data class CodedLearnedInput(val code: String, val choice: LearnedInput)

/** 独立数据库，不迁移或清空既有 Rime 用户库和剪贴板库。 */
internal class LocalInputStore(context: Context, name: String = "local_input.db", private val now: () -> Long = System::currentTimeMillis) :
    SQLiteOpenHelper(context.applicationContext, name, null, 10) {
    private val json = Json { ignoreUnknownKeys = true }
    override fun onCreate(db: SQLiteDatabase) {
        createPendingLearning(db)
        createReportTables(db)
        ReportImageIndex.createTables(db)
        createPersonalWords(db)
        createDictionarySyncTables(db)
        createDictionaryAdditionTables(db)
        createDictionaryHabitTables(db)
        db.execSQL("CREATE TABLE pending_event (id TEXT PRIMARY KEY NOT NULL, payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE event_target (event_id TEXT NOT NULL, target TEXT NOT NULL, PRIMARY KEY(event_id,target))")
        db.execSQL("CREATE INDEX event_target_url ON event_target(target)")
        db.execSQL("CREATE TABLE learned_input (code TEXT NOT NULL, text TEXT NOT NULL, count INTEGER NOT NULL, last_used INTEGER NOT NULL, weight REAL NOT NULL DEFAULT 0, PRIMARY KEY(code,text))")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 10) createPendingLearning(db)
        if(oldVersion < 9) {
            createDictionaryHabitTables(db)
            createDictionarySyncTables(db)
            if(!hasColumn(db,"dictionary_remote_choice","version")) db.execSQL("ALTER TABLE dictionary_remote_choice ADD COLUMN version INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 8) {
            // IF NOT EXISTS 同时兼容只含部分旧业务表的历史库。
            createDictionarySyncTables(db)
            createDictionaryAdditionTables(db)
        }
        if (oldVersion < 4) createPersonalWords(db)
        if (oldVersion < 3) createReportTables(db)
        if (oldVersion < 6 && !hasColumn(db, "pending_report", "online_confirmed_at")) {
            db.execSQL("ALTER TABLE pending_report ADD COLUMN online_confirmed_at INTEGER")
        }
        if (oldVersion < 7) {
            // v5 及更早版本只有固定线上目标；目标关联仅会在该目标明确确认后删除。
            // 只在一次性升级中恢复这项历史事实，运行期绝不根据 URL 缺失推断线上成功。
            db.execSQL(
                """UPDATE pending_report SET online_confirmed_at=?
                   WHERE online_confirmed_at IS NULL AND kind IN ('chat_asset','chat_messages')
                     AND EXISTS(SELECT 1 FROM report_target t WHERE t.report_id=pending_report.id)
                     AND NOT EXISTS(SELECT 1 FROM report_target t WHERE t.report_id=pending_report.id AND t.target=?)""",
                arrayOf(now(), LEGACY_ONLINE_TARGET),
            )
        }
        if (oldVersion < 8) retainRemoteWords(db)
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE learned_input ADD COLUMN weight REAL NOT NULL DEFAULT 0")
            db.execSQL("UPDATE learned_input SET weight=count")
        }
        if (oldVersion < 10) ReportImageIndex.createTables(db)
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
        db.execSQL("CREATE TABLE pending_report (id TEXT PRIMARY KEY NOT NULL, kind TEXT NOT NULL, payload TEXT NOT NULL, online_confirmed_at INTEGER)")
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
            if (inserted != -1L) ReportImageIndex.index(db, report.id, report.kind,
                report.payload.take(1024), report.payload.toByteArray(Charsets.UTF_8).size.toLong()) { report.payload }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized fun reportTargets(): List<String> = readableDatabase.rawQuery("SELECT DISTINCT target FROM report_target", null).use { c ->
        buildList { while(c.moveToNext()) add(c.getString(0)) }
    }

    /** 域名变化只改写尚未确认的线上投递目标；正文和电脑目标保持原状。 */
    @Synchronized fun replaceTarget(oldTarget: String, newTarget: String) {
        if (oldTarget == newTarget) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL(
                "INSERT OR IGNORE INTO event_target(event_id,target) SELECT event_id,? FROM event_target WHERE target=?",
                arrayOf(newTarget, oldTarget),
            )
            db.delete("event_target", "target=?", arrayOf(oldTarget))
            db.execSQL(
                """INSERT OR IGNORE INTO report_target(report_id,target,attempted_at)
                   SELECT report_id,?,attempted_at FROM report_target WHERE target=?""",
                arrayOf(newTarget, oldTarget),
            )
            db.delete("report_target", "target=?", arrayOf(oldTarget))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized fun hasPendingImages(): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM pending_report WHERE kind='chat_asset' LIMIT 1", null,
    ).use { it.moveToFirst() }

    @Synchronized fun pendingReports(
        target: String, limit: Int = 20, includeLocation: Boolean = true,
        maxImageBytes: () -> Long = { Long.MAX_VALUE },
        beginImageRead: () -> java.io.Closeable? = { java.io.Closeable {} },
    ): List<PendingReport> {
        val budget = maxImageBytes().coerceAtLeast(0)
        val db = writableDatabase
        // 有界迁移旧队列；暂停期间不读取旧图，未索引依赖保守等待。
        val imagePermit = if (budget > 0) beginImageRead() else null
        return try {
            ReportImageIndex.indexPending(db, target, imagePermit != null) { id, length -> readReportPayload(id, length) }
            // 无许可必须在LIMIT之前排除图片，不能让前20张图遮蔽普通报告。
            val queryBudget = if (imagePermit != null) budget else 0L
            db.rawQuery(
            """SELECT r.id,r.kind,CASE WHEN r.kind='chat_asset' THEN 0 ELSE LENGTH(r.payload) END,m.payload_bytes
               FROM pending_report r JOIN report_target t ON t.report_id=r.id
               LEFT JOIN report_image_meta m ON m.report_id=r.id
               WHERE t.target=? AND (?='1' OR r.kind!='location')
                 AND (r.kind!='chat_asset' OR (m.payload_bytes<=? AND ?>0))
                 AND (r.kind!='chat_messages' OR (m.dependencies_valid=1
                   AND NOT EXISTS (
                     SELECT 1 FROM report_image_dependency d
                     JOIN report_image_meta a ON a.asset_sha256=d.sha256
                     JOIN report_target at ON at.report_id=a.report_id
                     WHERE d.report_id=r.id AND at.target=t.target)
                   AND NOT EXISTS (
                     SELECT 1 FROM pending_report a JOIN report_target at ON at.report_id=a.id
                     LEFT JOIN report_image_meta am ON am.report_id=a.id
                     WHERE a.kind='chat_asset' AND at.target=t.target
                       AND (am.report_id IS NULL OR am.asset_sha256 IS NULL)
                       AND EXISTS (SELECT 1 FROM report_image_dependency ud WHERE ud.report_id=r.id))))
               ORDER BY t.attempted_at,
                 CASE r.kind WHEN 'chat_messages' THEN 0 WHEN 'chat_asset' THEN 1 ELSE 2 END,r.rowid LIMIT ?""",
            arrayOf(target, if (includeLocation) "1" else "0", queryBudget.toString(), queryBudget.toString(), limit.coerceIn(1,20).toString()),
        ).use { c -> buildList {
            var characters = 0L
            var imageBytes = 0L
            while (c.moveToNext()) {
                var length = c.getInt(2)
                val kind = c.getString(1)
                if (kind == "chat_asset") {
                    val bytes = c.getLong(3)
                    // 已持有本批读取许可，但用户可能重新打字，逐图再核对资格。
                    if (bytes > maxImageBytes().coerceAtLeast(0) - imageBytes) continue
                    if (isNotEmpty() && characters + bytes > 262_144) break
                    length = db.rawQuery("SELECT LENGTH(payload) FROM pending_report WHERE id=?", arrayOf(c.getString(0))).use {
                        check(it.moveToFirst()) { "Missing durable report" }; it.getInt(0)
                    }
                    imageBytes += bytes
                }
                if (isNotEmpty() && characters + length > 262_144) break
                add(PendingReport(c.getString(0), kind, readReportPayload(c.getString(0), length)))
                characters += length
            }
        } }
        } finally { imagePermit?.close() }
    }

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
    @Synchronized fun acknowledgeReports(target: String, ids: List<String>, onlineTarget: String? = null) {
        val db=writableDatabase
        db.beginTransaction()
        try {
            if (target == onlineTarget) ids.forEach {
                db.execSQL("UPDATE pending_report SET online_confirmed_at=? WHERE id=?", arrayOf(now(), it))
            }
            ids.forEach { db.delete("report_target","report_id=? AND target=?",arrayOf(it,target)) }
            db.execSQL("DELETE FROM pending_report WHERE NOT EXISTS(SELECT 1 FROM report_target t WHERE t.report_id=pending_report.id)")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** 线上未确认的记录没有时间戳，绝不参与过期删除。 */
    @Synchronized fun pruneExpiredLocalChatReports(onlineTarget: String, retentionMs: Long) {
        require(retentionMs >= 0)
        val cutoff = now() - retentionMs
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL(
                """DELETE FROM report_target
                   WHERE target!=? AND report_id IN (
                     SELECT id FROM pending_report
                     WHERE kind IN ('chat_asset','chat_messages')
                       AND online_confirmed_at IS NOT NULL AND online_confirmed_at<=?
                   )""",
                arrayOf(onlineTarget, cutoff),
            )
            db.execSQL("DELETE FROM pending_report WHERE NOT EXISTS(SELECT 1 FROM report_target t WHERE t.report_id=pending_report.id)")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean =
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            var found = false
            while (cursor.moveToNext() && !found) found = cursor.getString(nameIndex) == column
            found
        }

    private fun createPendingLearning(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS pending_learning (id TEXT PRIMARY KEY NOT NULL,choices TEXT NOT NULL,targets TEXT NOT NULL,selected_at INTEGER NOT NULL)")
    }

    /** 尚在纠错观察期的奖励持久化，但绝不进入报告队列/备份快照。 */
    @Synchronized fun stageLearning(id: String, choices: List<PendingChoice>, targets: List<String>) {
        settleLearning()
        require(choices.isNotEmpty() && choices.all { validCode(it.code) && it.text.length in 1..30 && it.text.all { ch -> ch in '\u4e00'..'\u9fff' } })
        writableDatabase.insertWithOnConflict("pending_learning", null, ContentValues().apply {
            put("id", id); put("choices", json.encodeToString(ListSerializer(PendingChoice.serializer()), choices))
            put("targets", json.encodeToString(ListSerializer(String.serializer()), targets.distinct()))
            put("selected_at", now())
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    @Synchronized fun cancelLearning(id: String): Boolean =
        writableDatabase.delete("pending_learning", "id=? AND selected_at>=?", arrayOf(id, (now() - CorrectionLearningTracker.REWARD_WINDOW_MS).toString())) > 0

    /** 插入正式学习、入队报告和删除临时奖励共用事务；进程重启/重复结算不产生重复点击。 */
    @Synchronized fun settleLearning() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val pending = db.rawQuery("SELECT id,choices,targets,selected_at FROM pending_learning WHERE selected_at<? ORDER BY selected_at,rowid",
                arrayOf((now() - CorrectionLearningTracker.REWARD_WINDOW_MS).toString())).use { c -> buildList {
                while (c.moveToNext()) add(arrayOf(c.getString(0), c.getString(1), c.getString(2), c.getLong(3).toString()))
            } }
            pending.forEach { (id, choices, targets, timestamp) ->
                val destinations = json.decodeFromString(ListSerializer(String.serializer()), targets)
                json.decodeFromString(ListSerializer(PendingChoice.serializer()), choices).forEach {
                    learnAt(it.code, it.text, destinations, it.pinyin, timestamp.toLong())
                }
                db.delete("pending_learning", "id=?", arrayOf(id))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun temporaryChoices(): List<CodedLearnedInput> = readableDatabase.rawQuery(
        "SELECT choices,selected_at FROM pending_learning", null,
    ).use { c -> buildList {
        while (c.moveToNext()) {
            val at = c.getLong(1)
            json.decodeFromString(ListSerializer(PendingChoice.serializer()), c.getString(0)).forEach {
                add(CodedLearnedInput(it.code, LearnedInput(it.text, 1, 1.0, at)))
            }
        }
    } }

    @Synchronized @JvmOverloads fun learn(code: String, text: String, targets: List<String> = emptyList(), pinyin: String = "") {
        settleLearning()
        learnAt(code, text, targets, pinyin, now())
    }

    private fun learnAt(code: String, text: String, targets: List<String>, pinyin: String, selectedAt: Long) {
        if (!validCode(code) || text.length !in 1..30 || text.any { it !in '\u4e00'..'\u9fff' }) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            PersonalWordReading.normalize(text, pinyin)?.takeIf { PersonalWordReading.matches(code, it) }?.let {
                rememberWord(text, it, "selection")
            }
            val old = db.rawQuery("SELECT weight,last_used FROM learned_input WHERE code=? AND text=?", arrayOf(code, text)).use { c ->
                if (c.moveToFirst()) c.getDouble(0) to c.getLong(1) else 0.0 to 0L
            }
            val timestamp = maxOf(selectedAt, old.second)
            val weight = PersonalCandidateRanker.decay(old.first, old.second, timestamp) +
                PersonalCandidateRanker.decay(1.0, selectedAt, timestamp)
            db.execSQL("INSERT OR IGNORE INTO learned_input(code,text,count,last_used) VALUES(?,?,0,0)", arrayOf(code, text))
            db.execSQL("UPDATE learned_input SET count=count+1,last_used=?,weight=? WHERE code=? AND text=?", arrayOf<Any>(timestamp, weight, code, text))
            if (targets.isNotEmpty()) {
                val count = db.rawQuery("SELECT count FROM learned_input WHERE code=? AND text=?", arrayOf(code,text)).use { c -> c.moveToFirst(); c.getLong(0) }
                val payload = buildJsonObject {
                    put("code",code); put("text",text); put("count",count); put("weight",weight); put("last_used",timestamp)
                }.toString()
                enqueueReport(PendingReport(java.util.UUID.randomUUID().toString(),"personal_choice",payload),targets)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    @Synchronized fun learned(code: String): List<LearnedInput> = effectiveChoices("code=?", arrayOf(code))
        .map { it.choice }.sortedWith(compareByDescending<LearnedInput> {
            PersonalCandidateRanker.decay(it.weight, it.lastUsed, now())
        }.thenByDescending { it.lastUsed }.thenBy { it.text }).take(64)

    /** 合法三键末音节补全也查询相关证据，读音边界由候选层校验；只将独立的远端证据加入，远端记录不写回本机学习表。 */
    @Synchronized fun relatedLearned(code: String): List<CodedLearnedInput> {
        if (code.length !in 3..30 || code.any { it !in '2'..'9' }) return learned(code).map { CodedLearnedInput(code,it) }
        val prefixes=(3..code.length).map { code.take(it) }
        val placeholders=prefixes.joinToString(",") { "?" }
        return effectiveChoices("(code GLOB ? OR code IN ($placeholders))", (listOf("$code*")+prefixes).toTypedArray())
    }

    private fun effectiveChoices(where: String, args: Array<String>): List<CodedLearnedInput> {
        settleLearning()
        // 同来源跨管理快照/加法通道只选较新一份，再合并不同设备的真实证据。
        val rows=readableDatabase.rawQuery(
            "SELECT code,text,count,weight,last_used,device_id,version FROM (SELECT code,text,count,weight,last_used,'' AS device_id,0 AS version FROM learned_input UNION ALL SELECT code,text,count,weight,last_used,device_id,version FROM dictionary_remote_choice UNION ALL SELECT code,text,count,weight,last_used,device_id,version FROM dictionary_added_habit) WHERE $where AND text NOT IN (SELECT text FROM dictionary_policy WHERE status!='enabled')", args,
        ).use { c -> buildList {
            while(c.moveToNext()) add(Triple(c.getString(5),c.getLong(6),CodedLearnedInput(c.getString(0),LearnedInput(c.getString(1),c.getLong(2),c.getDouble(3),c.getLong(4)))))
        } }.groupBy { Triple(it.first,it.third.code,it.third.choice.text) }.values.map { values ->
            values.maxWith(compareBy<Triple<String,Long,CodedLearnedInput>> { it.second }.thenBy { it.third.choice.lastUsed }.thenBy { it.third.choice.count }).third
        }
        val temporary = temporaryChoices().filter { record ->
            val matches = if (where == "code=?") record.code == args[0]
                else record.code.startsWith(args[0].removeSuffix("*")) || record.code in args.drop(1)
            matches && readableDatabase.rawQuery("SELECT 1 FROM dictionary_policy WHERE text=? AND status!='enabled'", arrayOf(record.choice.text)).use { !it.moveToFirst() }
        }
        return (rows + temporary).groupBy { it.code to it.choice.text }.map { (key,values) ->
            val at=values.maxOf { it.choice.lastUsed }
            CodedLearnedInput(key.first, LearnedInput(key.second,values.sumOf { it.choice.count },
                values.sumOf { PersonalCandidateRanker.decay(it.choice.weight,it.choice.lastUsed,at) },at))
        }
    }

    private fun createPersonalWords(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE personal_word (text TEXT NOT NULL, pinyin TEXT NOT NULL, full_code TEXT NOT NULL, source TEXT NOT NULL, PRIMARY KEY(text,pinyin,source))")
        db.execSQL("CREATE INDEX personal_word_code ON personal_word(full_code)")
    }

    /** 导入与本机点击分开，重复导入不增加次数，也不创建上传事件。未知读音只保留原词。 */
    @Synchronized fun rememberWord(text: String, pinyin: String, source: String): Boolean {
        if (text.length !in 1..30 || text.any { it !in '\u4e00'..'\u9fff' }) return false
        require(source == "selection" || source == "system_dictionary")
        val reading = PersonalWordReading.normalize(text, pinyin).orEmpty()
        if (source == "selection" && reading.isEmpty()) return false
        writableDatabase.insertWithOnConflict("personal_word", null, ContentValues().apply {
            put("text", text); put("pinyin", reading)
            put("full_code", T9Lexicon.digits(reading.replace(" ", ""))); put("source", source)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        return true
    }

    @Synchronized fun personalWords(code: String, preferredOnly: Boolean = false): List<T9Candidate> {
        if (code.length !in 3..30 || code.any { it !in '2'..'9' }) return emptyList()
        val sources = if (preferredOnly) "SELECT text,pinyin,full_code FROM dictionary_added_word WHERE preferred=1"
            else "SELECT text,pinyin,full_code FROM personal_word UNION ALL SELECT text,pinyin,full_code FROM dictionary_remote_word UNION ALL SELECT text,pinyin,full_code FROM dictionary_added_word"
        return readableDatabase.rawQuery(
            "SELECT DISTINCT text,pinyin FROM ($sources) WHERE full_code GLOB ? AND text NOT IN (SELECT text FROM dictionary_policy WHERE status!='enabled') ORDER BY text,pinyin", arrayOf("$code*"),
        ).use { c -> buildList {
            while (c.moveToNext()) {
                val reading = c.getString(1)
                if (PersonalWordReading.matches(code, reading)) add(T9Candidate(c.getString(0), reading))
            }
        } }
    }

    private fun createDictionaryAdditionTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS dictionary_added_word (text TEXT NOT NULL,pinyin TEXT NOT NULL,full_code TEXT NOT NULL,preferred INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(text,pinyin))")
        db.execSQL("CREATE INDEX IF NOT EXISTS dictionary_added_word_code ON dictionary_added_word(full_code)")
        db.execSQL("CREATE TABLE IF NOT EXISTS dictionary_addition_cursor (target TEXT PRIMARY KEY NOT NULL,cursor INTEGER NOT NULL)")
    }

    /** 旧换机恢复词也算手机已有词；仅固化读音，不复制来源次数。 */
    private fun retainRemoteWords(db: SQLiteDatabase) {
        db.execSQL("INSERT OR IGNORE INTO dictionary_added_word(text,pinyin,full_code,preferred) SELECT text,pinyin,full_code,0 FROM dictionary_remote_word WHERE pinyin!=''")
    }

    /** 独立并集层，旧快照替换、另一后台小集合均不能删除。坏批全拒绝。 */
    @Synchronized fun mergeDictionaryAdditions(entries: List<DictionaryAddition>) {
        require(entries.size <= 500 && entries.all { it.valid() })
        val db=writableDatabase
        db.beginTransaction()
        try {
            entries.forEach { e ->
                db.execSQL("INSERT OR IGNORE INTO dictionary_added_word(text,pinyin,full_code,preferred) VALUES(?,?,?,?)",
                    arrayOf<Any>(e.text,e.pinyin,T9Lexicon.digits(e.pinyin.replace(" ","")),if(e.preferred) 1 else 0))
                if(e.preferred) db.execSQL("UPDATE dictionary_added_word SET preferred=1 WHERE text=? AND pinyin=?",arrayOf(e.text,e.pinyin))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** 游标与词表同库：数据库重建后从0重放，不受旧Preferences影响。 */
    @Synchronized fun dictionaryAdditionCursor(target: String): Long = readableDatabase.rawQuery(
        "SELECT cursor FROM dictionary_addition_cursor WHERE target=?",arrayOf(target),
    ).use { if(it.moveToFirst()) it.getLong(0) else 0L }

    @Synchronized fun saveDictionaryAdditionCursor(target: String, cursor: Long) {
        require(cursor in 0..9_007_199_254_740_991L)
        writableDatabase.execSQL("INSERT OR REPLACE INTO dictionary_addition_cursor(target,cursor) VALUES(?,?)",arrayOf<Any>(target,cursor))
    }

    private fun createDictionaryHabitTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS dictionary_added_habit(device_id TEXT NOT NULL,code TEXT NOT NULL,text TEXT NOT NULL,count INTEGER NOT NULL,weight REAL NOT NULL,last_used INTEGER NOT NULL,version INTEGER NOT NULL,PRIMARY KEY(device_id,code,text))")
        db.execSQL("CREATE INDEX IF NOT EXISTS dictionary_added_habit_code ON dictionary_added_habit(code)")
        db.execSQL("CREATE TABLE IF NOT EXISTS dictionary_habit_cursor(target TEXT PRIMARY KEY NOT NULL,cursor INTEGER NOT NULL)")
    }

    @Synchronized fun mergeDictionaryHabits(entries: List<DictionaryHabit>, selfDeviceId: String) {
        require(entries.size<=500 && entries.all { it.valid() })
        val db=writableDatabase
        db.beginTransaction()
        try {
            // 设备身份变化/历史恢复也不能把自身副本叠加成本机点击。
            db.delete("dictionary_added_habit","device_id=?",arrayOf(selfDeviceId))
            entries.filter { it.deviceId!=selfDeviceId }.forEach { incoming ->
                // 管理快照先到也采用同一来源的较新证据，不能因旧加法页稍后到达而降级。
                val e=db.rawQuery("SELECT count,weight,last_used,version FROM dictionary_remote_choice WHERE device_id=? AND code=? AND text=?",
                    arrayOf(incoming.deviceId,incoming.code,incoming.text)).use { c ->
                    if(c.moveToFirst() && c.getLong(3)>incoming.version) incoming.copy(count=c.getLong(0),weight=c.getDouble(1),lastUsed=c.getLong(2),version=c.getLong(3))
                    else incoming
                }
                val old=db.rawQuery("SELECT version FROM dictionary_added_habit WHERE device_id=? AND code=? AND text=?",arrayOf(e.deviceId,e.code,e.text)).use { if(it.moveToFirst()) it.getLong(0) else -1L }
                if(e.version>old) check(db.insertWithOnConflict("dictionary_added_habit",null,ContentValues().apply {
                    put("device_id",e.deviceId);put("code",e.code);put("text",e.text);put("count",e.count)
                    put("weight",e.weight);put("last_used",e.lastUsed);put("version",e.version)
                },SQLiteDatabase.CONFLICT_REPLACE) != -1L)
            }
            db.setTransactionSuccessful()
        } finally {db.endTransaction()}
    }
    @Synchronized fun dictionaryHabitCursor(target: String): Long = readableDatabase.rawQuery(
        "SELECT cursor FROM dictionary_habit_cursor WHERE target=?",arrayOf(target),
    ).use { if(it.moveToFirst()) it.getLong(0) else 0L }
    @Synchronized fun saveDictionaryHabitCursor(target: String,cursor: Long) {
        require(cursor in 0..9_007_199_254_740_991L)
        writableDatabase.execSQL("INSERT OR REPLACE INTO dictionary_habit_cursor(target,cursor) VALUES(?,?)",arrayOf<Any>(target,cursor))
    }

    private fun createDictionarySyncTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS dictionary_remote_choice (device_id TEXT NOT NULL,code TEXT NOT NULL,text TEXT NOT NULL,count INTEGER NOT NULL,weight REAL NOT NULL,last_used INTEGER NOT NULL,version INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(device_id,code,text))")
        db.execSQL("CREATE INDEX IF NOT EXISTS dictionary_remote_choice_code ON dictionary_remote_choice(code)")
        db.execSQL("CREATE TABLE IF NOT EXISTS dictionary_remote_word (device_id TEXT NOT NULL,text TEXT NOT NULL,pinyin TEXT NOT NULL,full_code TEXT NOT NULL,source TEXT NOT NULL,PRIMARY KEY(device_id,text,pinyin,source))")
        db.execSQL("CREATE INDEX IF NOT EXISTS dictionary_remote_word_code ON dictionary_remote_word(full_code)")
        db.execSQL("CREATE TABLE IF NOT EXISTS dictionary_policy (text TEXT PRIMARY KEY NOT NULL,status TEXT NOT NULL)")
    }

    /** 只导出本机原始数据。恢复层永不上传，避免新旧手机无限累计相同权重。 */
    @Synchronized fun dictionaryExport(): List<DictionaryRecord> {
        settleLearning()
        return buildList {
        readableDatabase.rawQuery("SELECT code,text,count,weight,last_used FROM learned_input ORDER BY code,text",null).use { c ->
            while(c.moveToNext()) add(DictionaryRecord("choice",c.getString(1),c.getString(0),"","selection",c.getLong(2),c.getDouble(3),c.getLong(4)))
        }
        readableDatabase.rawQuery("SELECT text,pinyin,source FROM personal_word ORDER BY text,pinyin,source",null).use { c ->
            while(c.moveToNext()) add(DictionaryRecord("word",c.getString(0),"",c.getString(1),c.getString(2),0,0.0,0))
        }
        }.filter { it.valid() }
    }

    /** 次数快照替换避免重复加权；已有词保留并集，策略仅显式变更，缺项不删词/复活。 */
    @Synchronized fun applyDictionarySnapshot(snapshot: DictionarySnapshot, selfDeviceId: String) {
        require(snapshot.revision.matches(Regex("[a-f0-9]{64}")))
        require(snapshot.entries.size <= 100_000 && snapshot.entries.all { it.deviceId.isNotEmpty() && it.valid() })
        require(snapshot.policies.all { it.status in listOf("enabled","disabled","deleted") && it.text.length in 1..30 && it.text.all { ch -> ch in '\u4e00'..'\u9fff' } })
        val db=writableDatabase
        db.beginTransaction()
        try {
            retainRemoteWords(db)
            db.delete("dictionary_remote_word",null,null); db.delete("dictionary_remote_choice",null,null)
            snapshot.policies.forEach { p -> db.insertWithOnConflict("dictionary_policy",null,ContentValues().apply { put("text",p.text);put("status",p.status) },SQLiteDatabase.CONFLICT_REPLACE) }
            snapshot.entries.filter { it.deviceId!=selfDeviceId }.forEach { e ->
                val values=ContentValues().apply { put("device_id",e.deviceId);put("text",e.text) }
                if(e.kind=="choice") {
                    values.put("code",e.code);values.put("count",e.count);values.put("weight",e.weight);values.put("last_used",e.lastUsed);values.put("version",e.version)
                    db.insertOrThrow("dictionary_remote_choice",null,values)
                    // 已经进入加法并集的同源习惯只前进不回退；旧/空管理快照不能抹掉较新证据。
                    db.execSQL("UPDATE dictionary_added_habit SET count=?,weight=?,last_used=?,version=? WHERE device_id=? AND code=? AND text=? AND version<?",
                        arrayOf<Any>(e.count,e.weight,e.lastUsed,e.version,e.deviceId,e.code,e.text,e.version))
                } else {
                    values.put("pinyin",e.pinyin);values.put("full_code",T9Lexicon.digits(e.pinyin.replace(" ","")));values.put("source",e.source)
                    db.insertOrThrow("dictionary_remote_word",null,values)
                }
            }
            db.setTransactionSuccessful()
        } finally {db.endTransaction()}
    }

    private fun validCode(code: String): Boolean =
        (code.length in 1..30 && code.all { it in '2'..'9' }) ||
            (code.length in 2..30 && code.all { it in 'a'..'z' })

    private companion object {
        const val LEGACY_ONLINE_TARGET = "https://my.dog8ball.com"
    }
}
