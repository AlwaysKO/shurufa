package com.yuyan.imemodule.data.usage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

internal fun UsageRecord.toJson() = JSONObject().put("id",id).put("kind",kind)
    .put("package_name",packageName ?: JSONObject.NULL).put("app_name",appName ?: JSONObject.NULL)
    .put("start_ms",startMs).put("end_ms",endMs).put("end_reason",endReason)
internal fun usageRecordFromJson(j: JSONObject) = UsageRecord(j.getString("id"),j.getString("kind"),
    if(j.isNull("package_name")) null else j.getString("package_name"),
    if(j.isNull("app_name")) null else j.getString("app_name"),j.getLong("start_ms"),j.getLong("end_ms"),j.getString("end_reason"))

/** Transactions bind cursor advancement to per-target delivery records.
 * The device-owned queue must not travel through system or keyboard database backups.
 */
internal class UsageStore(context: Context): SQLiteOpenHelper(context.applicationContext,java.io.File(context.noBackupFilesDir,"app_usage.db").absolutePath,null,2), java.io.Closeable {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE state (id INTEGER PRIMARY KEY CHECK(id=1), payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE record (id TEXT PRIMARY KEY, end_ms INTEGER NOT NULL, payload TEXT NOT NULL, start_ms INTEGER NOT NULL CHECK(end_ms>start_ms), online_confirmed_at INTEGER)")
        db.execSQL("CREATE TABLE delivery (record_id TEXT NOT NULL, target TEXT NOT NULL, acknowledged INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(record_id,target))")
        db.execSQL("CREATE INDEX pending_delivery ON delivery(target,acknowledged)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE record ADD COLUMN online_confirmed_at INTEGER")
    }
    fun state(): UsageState? = readableDatabase.rawQuery("SELECT payload FROM state WHERE id=1",null).use { c ->
        if(!c.moveToFirst()) return@use null
        val j=JSONObject(c.getString(0)); val a=j.optJSONObject("active"); val candidate=j.optJSONObject("candidate")
        UsageState(j.getLong("cursor"),a?.let { UsageActive(it.getString("pkg"),it.getString("token"),it.getLong("start"),if(it.isNull("pause")) null else it.getLong("pause")) },
            j.optBoolean("blocked"),j.optBoolean("suspended"),j.optBoolean("locked"),j.optInt("boot",-1),j.optLong("elapsed",0),j.optLong("observed",j.getLong("cursor")),
            candidate?.let { UsageActive(it.getString("pkg"),it.getString("token"),it.getLong("start")) })
    }
    fun save(result: UsageReduction, targets: List<String>) {
        val db=writableDatabase
        db.beginTransaction()
        try {
            result.records.forEach { r ->
                // A constraint violation must roll back the cursor as well as this batch.
                db.execSQL("INSERT INTO record(id,start_ms,end_ms,payload) SELECT ?,?,?,? WHERE NOT EXISTS (SELECT 1 FROM record WHERE id=?)",arrayOf<Any>(r.id,r.startMs,r.endMs,r.toJson().toString(),r.id))
                targets.distinct().forEach { target ->
                    db.execSQL("INSERT OR IGNORE INTO delivery(record_id,target) VALUES(?,?)",arrayOf(r.id,target))
                }
            }
            val s=result.state
            val active=s.active?.let { JSONObject().put("pkg",it.packageName).put("token",it.token).put("start",it.start).put("pause",it.pausedAt ?: JSONObject.NULL) }
            val candidate=s.candidate?.let { JSONObject().put("pkg",it.packageName).put("token",it.token).put("start",it.start) }
            val payload=JSONObject().put("cursor",s.cursor).put("active",active ?: JSONObject.NULL).put("blocked",s.blocked)
                .put("suspended",s.suspended).put("locked",s.locked).put("boot",s.boot).put("elapsed",s.elapsed).put("observed",s.observedAt).put("candidate",candidate ?: JSONObject.NULL).toString()
            db.insertWithOnConflict("state",null,ContentValues().apply { put("id",1); put("payload",payload) },SQLiteDatabase.CONFLICT_REPLACE).also { check(it!=-1L) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun targets(): List<String> = readableDatabase.rawQuery("SELECT DISTINCT target FROM delivery WHERE acknowledged=0",null).use { c -> buildList { while(c.moveToNext()) add(c.getString(0)) } }
    fun pending(target: String): List<UsageRecord> = readableDatabase.rawQuery(
        "SELECT r.payload FROM record r JOIN delivery d ON r.id=d.record_id WHERE d.target=? AND d.acknowledged=0 ORDER BY r.end_ms,r.id LIMIT 200",arrayOf(target)
    ).use { c -> buildList { while(c.moveToNext()) add(usageRecordFromJson(JSONObject(c.getString(0)))) } }
    fun acknowledge(target: String, ids: List<String>, onlineTarget: String? = null, now: Long = System.currentTimeMillis()) {
        val db=writableDatabase; db.beginTransaction()
        try {
            if (target == onlineTarget) ids.forEach { db.execSQL("UPDATE record SET online_confirmed_at=COALESCE(online_confirmed_at,?) WHERE id=?",arrayOf(now,it)) }
            ids.forEach { db.execSQL("UPDATE delivery SET acknowledged=1 WHERE record_id=? AND target=?",arrayOf(it,target)) }; db.setTransactionSuccessful() }
        finally { db.endTransaction() }
    }
    /** Pending local copies expire only seven days after explicit online confirmation. */
    fun prune(before: Long, onlineTarget: String? = null) {
        val db=writableDatabase; db.beginTransaction()
        try {
            if (onlineTarget != null) db.execSQL("""DELETE FROM record WHERE online_confirmed_at IS NOT NULL AND online_confirmed_at<=?
                AND NOT EXISTS (SELECT 1 FROM delivery d WHERE d.record_id=record.id AND d.target=? AND d.acknowledged=0)""",arrayOf(before,onlineTarget))
            db.execSQL("DELETE FROM record WHERE end_ms<? AND NOT EXISTS (SELECT 1 FROM delivery d WHERE d.record_id=record.id AND d.acknowledged=0)",arrayOf(before))
            db.execSQL("DELETE FROM delivery WHERE NOT EXISTS (SELECT 1 FROM record r WHERE r.id=delivery.record_id)")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}
