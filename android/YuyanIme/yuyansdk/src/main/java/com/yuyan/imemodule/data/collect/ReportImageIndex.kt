package com.yuyan.imemodule.data.collect

import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.json.*

/** 仅索引持久报告的大小与依赖，不复制 Base64；每个目标仍由 report_target 独立确认。 */
internal object ReportImageIndex {
    private val assetHash = Regex("""^\s*\{\s*"sha256"\s*:\s*"([a-fA-F0-9]{64})"""")

    fun createTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS report_image_meta (report_id TEXT PRIMARY KEY NOT NULL, asset_sha256 TEXT, payload_bytes INTEGER NOT NULL, dependencies_valid INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS report_image_hash ON report_image_meta(asset_sha256)")
        db.execSQL("CREATE TABLE IF NOT EXISTS report_image_dependency (report_id TEXT NOT NULL, sha256 TEXT NOT NULL, PRIMARY KEY(report_id,sha256))")
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS report_image_cleanup AFTER DELETE ON pending_report BEGIN
            DELETE FROM report_image_meta WHERE report_id=OLD.id;
            DELETE FROM report_image_dependency WHERE report_id=OLD.id;
            END""")
    }

    fun index(db: SQLiteDatabase, id: String, kind: String, prefix: String, bytes: Long, payload: () -> String) {
        if (kind != "chat_asset" && kind != "chat_messages") return
        val hash = if (kind == "chat_asset") assetHash.find(prefix)?.groupValues?.get(1) else null
        val dependencies = if (kind == "chat_messages") try {
            val root = Json.parseToJsonElement(payload()).jsonObject
            root["messages"]?.jsonArray.orEmpty().flatMap { message ->
                message.jsonObject["asset_sha256"]?.jsonArray.orEmpty().map {
                    it.jsonPrimitive.content.also { value -> require(value.matches(Regex("[a-fA-F0-9]{64}"))) }
                }
            }.distinct()
        } catch (_: Exception) { null } else emptyList()
        db.execSQL("INSERT OR IGNORE INTO report_image_meta(report_id,asset_sha256,payload_bytes,dependencies_valid) VALUES(?,?,?,?)",
            arrayOf(id, hash, bytes, if (dependencies != null) 1 else 0))
        dependencies.orEmpty().forEach { sha ->
            db.execSQL("INSERT OR IGNORE INTO report_image_dependency(report_id,sha256) VALUES(?,?)", arrayOf(id, sha))
        }
    }

    /** 一次最多索引 64 行。图只读首字段；大正文仍由调用方分片读取。 */
    fun indexPending(db: SQLiteDatabase, target: String, includeImages: Boolean, read: (String, Int) -> String) {
        db.beginTransaction()
        try {
            db.rawQuery("""SELECT r.id,r.kind,SUBSTR(r.payload,1,1024),LENGTH(CAST(r.payload AS BLOB)),LENGTH(r.payload)
                FROM pending_report r JOIN report_target t ON t.report_id=r.id
                LEFT JOIN report_image_meta m ON m.report_id=r.id
                WHERE t.target=? AND m.report_id IS NULL AND
                  (r.kind='chat_messages' OR (?='1' AND r.kind='chat_asset'))
                ORDER BY CASE r.kind WHEN 'chat_asset' THEN 0 ELSE 1 END,r.rowid LIMIT 64""",
                arrayOf(target, if (includeImages) "1" else "0"),
            ).use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    index(db, id, c.getString(1), c.getString(2), c.getLong(3)) { read(id, c.getInt(4)) }
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}
