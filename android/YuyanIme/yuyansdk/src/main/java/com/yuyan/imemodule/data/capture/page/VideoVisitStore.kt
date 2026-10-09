package com.yuyan.imemodule.data.capture.page

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 本机独立日志，不参与聊天联系人分组，也不进入系统备份。
 * 在后台串行调用；每次从事务内快照构建状态机，落盘失败不会先清掉内存中的访问。
 * completed 是待交接记录，尚无远端回执时不删除。网络交接由集成层负责。
 */
internal class VideoVisitStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext, File(context.noBackupFilesDir, "video_visits.db").absolutePath, null, 2,
) {
    private val json = Json { ignoreUnknownKeys = true }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE active (id INTEGER PRIMARY KEY CHECK(id=1), payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE completed (id TEXT PRIMARY KEY, entered_at INTEGER NOT NULL, payload TEXT NOT NULL, retry_after INTEGER NOT NULL DEFAULT 0, platform TEXT NOT NULL)")
        db.execSQL("CREATE INDEX completed_order ON completed(entered_at,id)")
        db.execSQL("CREATE INDEX completed_delivery ON completed(platform,retry_after,entered_at,id)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE completed ADD COLUMN retry_after INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE completed ADD COLUMN platform TEXT NOT NULL DEFAULT ''")
            // 旧版本 payload 格式原样保留；游标逐条解码，避免全量载入或依赖设备 JSON1 扩展。
            db.rawQuery("SELECT id,payload FROM completed", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val visit = json.decodeFromString<VideoVisit>(cursor.getString(1))
                    db.execSQL("UPDATE completed SET platform=? WHERE id=?", arrayOf(visit.platform, cursor.getString(0)))
                }
            }
            db.execSQL("CREATE INDEX completed_delivery ON completed(platform,retry_after,entered_at,id)")
        }
    }

    fun active(): VideoVisit? = readActive(readableDatabase)
    private fun readActive(db: SQLiteDatabase): VideoVisit? =
        db.rawQuery("SELECT payload FROM active WHERE id=1", null).use {
            if (it.moveToFirst()) json.decodeFromString<VideoVisit>(it.getString(0)) else null
        }

    fun enter(platform: String, videoKey: String, elapsed: Long, wallTime: Long, firstImage: String? = null,
        observationKind: VideoObservationKind = VideoObservationKind.CONFIRMED_VIDEO): VideoVisitChange =
        transaction { db, tracker ->
            val change = tracker.enter(platform, videoKey, elapsed, wallTime, observationKind)
            change.previous?.let { saveCompleted(db, it) }
            if (firstImage != null) tracker.attachFrame(change.active.id, videoKey, VideoFrameRole.FIRST, firstImage)
            change.copy(active = requireNotNull(tracker.snapshot()))
        }

    fun attachFrame(id: String, videoKey: String, role: VideoFrameRole, reference: String): Boolean =
        transaction { _, tracker -> tracker.attachFrame(id, videoKey, role, reference) }

    fun finish(id: String, elapsed: Long, wallTime: Long, reason: VideoExitReason): VideoVisit? =
        transaction { db, tracker ->
            tracker.finish(id, elapsed, wallTime, reason)?.also { saveCompleted(db, it) }
        }

    /** 仅在新运行会话初始化时调用。不能将上次的 elapsed 时间接到本次继续计时。 */
    fun recoverInterrupted() = transaction { db, tracker ->
        tracker.snapshot()?.let { visit ->
            tracker.finish(visit.id, visit.lastObservedElapsed, visit.enteredAt, VideoExitReason.INTERRUPTED)
                ?.also { saveCompleted(db, it) }
        }
    }

    /** 有界读取，不将离线积压全部加载到内存。 */
    fun completed(limit: Int = 100): List<VideoVisit> = readableDatabase.rawQuery(
        "SELECT payload FROM completed ORDER BY entered_at,id LIMIT ?", arrayOf(limit.coerceIn(0, 200).toString()),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(json.decodeFromString<VideoVisit>(cursor.getString(0))) } }

    /** SQL 单次有界读取；回拨后不让未来退避时间无限阻塞。 */
    fun due(now: Long, limit: Int = 20, platforms: Set<String> = setOf("wechat", "douyin")): List<VideoVisit> {
        val selected = platforms.intersect(setOf("wechat", "douyin")).toList()
        if (selected.isEmpty()) return emptyList()
        val placeholders = selected.joinToString(",") { "?" }
        return readableDatabase.rawQuery(
            "SELECT payload FROM completed WHERE platform IN ($placeholders) AND (retry_after<=? OR retry_after>?) " +
                "ORDER BY retry_after,entered_at,id LIMIT ?",
            (selected + listOf(now.toString(), (now + RETRY_MILLIS).toString(), limit.coerceIn(0, 200).toString())).toTypedArray(),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(json.decodeFromString<VideoVisit>(cursor.getString(0))) } }
    }

    fun defer(visit: VideoVisit, now: Long) {
        writableDatabase.execSQL("UPDATE completed SET retry_after=? WHERE id=? AND payload=?",
            arrayOf(now + RETRY_MILLIS, visit.id, json.encodeToString(visit)))
    }

    /** 精确 payload 与二次作用域守卫；撤权或换目标不能清理待传。 */
    fun acknowledge(visit: VideoVisit, allowed: () -> Boolean): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (!allowed()) return false
            val deleted = db.delete("completed", "id=? AND payload=?", arrayOf(visit.id, json.encodeToString(visit)))
            if (deleted != 1 || !allowed()) return false
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }

    private companion object { const val RETRY_MILLIS = 30_000L }

    private fun saveCompleted(db: SQLiteDatabase, visit: VideoVisit) {
        check(visit.reason != null)
        db.execSQL("INSERT INTO completed(id,entered_at,payload,platform) VALUES(?,?,?,?)",
            arrayOf<Any>(visit.id, visit.enteredAt, json.encodeToString(visit), visit.platform))
    }

    private fun <T> transaction(block: (SQLiteDatabase, VideoVisitTracker) -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val before = readActive(db)
            val tracker = VideoVisitTracker(before)
            val result = block(db, tracker)
            val active = tracker.snapshot()
            if (active != before) {
                if (active == null) db.execSQL("DELETE FROM active WHERE id=1")
                else db.execSQL("INSERT OR REPLACE INTO active(id,payload) VALUES(1,?)", arrayOf(json.encodeToString(active)))
            }
            db.setTransactionSuccessful()
            return result
        } finally { db.endTransaction() }
    }
}
