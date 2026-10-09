package com.yuyan.imemodule.data.capture.page

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal enum class PageWriteStatus { SAVED, DUPLICATE, FULL, INVALID, DISCARDED, NO_FRAME }
internal data class PageWriteResult(val status: PageWriteStatus, val id: String? = null)
internal data class PendingPageCapture(
    val id: String, val packageName: String, val kind: PageKind, val capturedAt: Long,
    val width: Int, val height: Int, val sha256: String,
)

/** 非聊天本地待交接记录，图片和元数据同一行原子提交。满时拒绝新记录，不淘汰未确认原图。 */
internal class PageCaptureOutbox(
    context: Context,
    private val maxBytes: Long = 32L * 1024 * 1024,
    private val maxRecords: Int = 100,
) : SQLiteOpenHelper(context.applicationContext,
    File(context.noBackupFilesDir, "page_capture_outbox.db").absolutePath, null, 2) {
    init { require(maxBytes in 1..32L * 1024 * 1024); require(maxRecords in 1..100) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE pages (
            id TEXT PRIMARY KEY, package_name TEXT NOT NULL, kind TEXT NOT NULL,
            captured_at INTEGER NOT NULL, width INTEGER NOT NULL, height INTEGER NOT NULL,
            sha256 TEXT NOT NULL, image BLOB NOT NULL, retry_after INTEGER NOT NULL DEFAULT 0,
            UNIQUE(package_name,kind,sha256))""")
        db.execSQL("CREATE INDEX pages_pending_order ON pages(captured_at,id)")
        createReceipts(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion == 1 && newVersion == 2)
        db.execSQL("ALTER TABLE pages ADD COLUMN retry_after INTEGER NOT NULL DEFAULT 0")
        createReceipts(db)
    }

    private fun createReceipts(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE page_receipts (
            package_name TEXT NOT NULL, kind TEXT NOT NULL, sha256 TEXT NOT NULL, id TEXT NOT NULL,
            PRIMARY KEY(package_name,kind,sha256))""")
    }

    /** 确认与原图删除同事务；撤权/目标变化仍不能确认。最多保留1000条无图回执。 */
    @Synchronized fun acknowledge(row: PendingPageCapture, authorized: () -> Boolean): Boolean {
        if (!authorized()) return false
        val db = writableDatabase
        db.beginTransaction()
        try {
            val count = db.rawQuery("SELECT COUNT(*) FROM pages WHERE id=? AND sha256=?",
                arrayOf(row.id, row.sha256)).use { it.moveToFirst(); it.getInt(0) }
            if (count != 1) return false
            db.execSQL("INSERT OR REPLACE INTO page_receipts(package_name,kind,sha256,id) VALUES(?,?,?,?)",
                arrayOf(row.packageName, row.kind.name, row.sha256, row.id))
            db.delete("pages", "id=? AND sha256=?", arrayOf(row.id, row.sha256))
            db.execSQL("DELETE FROM page_receipts WHERE rowid NOT IN (SELECT rowid FROM page_receipts ORDER BY rowid DESC LIMIT 1000)")
            if (!authorized()) return false
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }

    fun defer(row: PendingPageCapture, now: Long) {
        val until = now.coerceAtMost(Long.MAX_VALUE - 30_000) + 30_000
        writableDatabase.execSQL("UPDATE pages SET retry_after=? WHERE id=? AND sha256=?", arrayOf<Any>(until, row.id, row.sha256))
    }
    fun due(now: Long): List<PendingPageCapture> = readPending(100, now)

    /** 后台调用；capturedAt 是调用方冻结的取帧请求时间，不是视频播放位置。 */
    @Synchronized fun enqueue(
        packageName: String, frame: PageFrame, capturedAt: Long, authorized: () -> Boolean = { true },
    ): PageWriteResult {
        if (!authorized()) return PageWriteResult(PageWriteStatus.DISCARDED)
        val kind = frame.page.kind
        if (packageName !in setOf("com.tencent.mm", "com.ss.android.ugc.aweme") ||
            kind == null || kind == PageKind.CHAT || capturedAt <= 0 ||
            frame.width !in 1..8192 || frame.height !in 1..8192 || frame.bytes.size !in 1..3 * 1024 * 1024)
            return PageWriteResult(PageWriteStatus.INVALID)
        val bytes = frame.bytes.copyOf()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val existing = db.rawQuery("SELECT id FROM pages WHERE package_name=? AND kind=? AND sha256=? UNION ALL SELECT id FROM page_receipts WHERE package_name=? AND kind=? AND sha256=? LIMIT 1",
                arrayOf(packageName, kind.name, hash, packageName, kind.name, hash)).use { if (it.moveToFirst()) it.getString(0) else null }
            if (existing != null) return if (authorized()) PageWriteResult(PageWriteStatus.DUPLICATE, existing)
                else PageWriteResult(PageWriteStatus.DISCARDED)
            val full = db.rawQuery("SELECT COUNT(*),COALESCE(SUM(length(image)),0) FROM pages", null).use {
                it.moveToFirst(); it.getInt(0) >= maxRecords || it.getLong(1) > maxBytes - bytes.size
            }
            if (full) return PageWriteResult(PageWriteStatus.FULL)
            val id = UUID.randomUUID().toString()
            db.execSQL("INSERT INTO pages(id,package_name,kind,captured_at,width,height,sha256,image) VALUES(?,?,?,?,?,?,?,?)",
                arrayOf(id, packageName, kind.name, capturedAt, frame.width, frame.height, hash, bytes))
            if (!authorized()) return PageWriteResult(PageWriteStatus.DISCARDED)
            db.setTransactionSuccessful()
            return PageWriteResult(PageWriteStatus.SAVED, id)
        } finally { db.endTransaction() }
    }

    /** 截图前按最大单图预留空间检查；入队事务仍再次核容量，不能以此取代原子检查。 */
    fun hasCapacity(requiredBytes: Int = 3 * 1024 * 1024): Boolean {
        require(requiredBytes in 1..3 * 1024 * 1024)
        return readableDatabase.rawQuery("SELECT COUNT(*),COALESCE(SUM(length(image)),0) FROM pages", null).use {
            it.moveToFirst(); it.getInt(0) < maxRecords && it.getLong(1) <= maxBytes - requiredBytes
        }
    }

    /** 只读有界元信息；不得一次把积压图片全部装进内存。图片只由匹配的远端回执确认后删除。 */
    fun pending(limit: Int = 20): List<PendingPageCapture> = readPending(limit, null)
    private fun readPending(limit: Int, now: Long?): List<PendingPageCapture> = readableDatabase.rawQuery(
        "SELECT id,package_name,kind,captured_at,width,height,sha256 FROM pages ${if (now == null) "" else "WHERE retry_after<=? OR retry_after>?"} ORDER BY ${if (now == null) "" else "retry_after,"}captured_at,id LIMIT ?",
        if (now == null) arrayOf(limit.coerceIn(0, 100).toString()) else arrayOf(now.toString(),
            (now.coerceAtMost(Long.MAX_VALUE - 30_000) + 30_000).toString(), limit.coerceIn(0, 100).toString()),
    ).use { rows -> buildList {
        while (rows.moveToNext()) add(PendingPageCapture(rows.getString(0), rows.getString(1), PageKind.valueOf(rows.getString(2)),
            rows.getLong(3), rows.getInt(4), rows.getInt(5), rows.getString(6)))
    } }
    fun image(id: String): ByteArray? {
        val db = readableDatabase
        val info = db.rawQuery("SELECT length(image),sha256 FROM pages WHERE id=?", arrayOf(id)).use {
            if (it.moveToFirst()) it.getInt(0) to it.getString(1) else null
        } ?: return null
        check(info.first in 1..3 * 1024 * 1024)
        // Android CursorWindow放不下3MiB整行；每次只读取64KiB，累计分配仍受单图上限保护。
        val bytes = ByteArray(info.first)
        var offset = 0
        while (offset < bytes.size) {
            val count = minOf(64 * 1024, bytes.size - offset)
            val chunk = db.rawQuery("SELECT substr(image,?,?) FROM pages WHERE id=?",
                arrayOf((offset + 1).toString(), count.toString(), id)).use {
                if (it.moveToFirst()) it.getBlob(0) else null
            } ?: return null
            check(chunk.size == count)
            chunk.copyInto(bytes, offset)
            offset += count
        }
        check(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } == info.second)
        return bytes
    }
}

/** 仅对已通过同帧安全/窗口校验的图片调用。普通任务取消不能丢掉已接收的帧，撤同意仍拒绝。 */
internal suspend fun persistAcceptedPage(
    outbox: PageCaptureOutbox, packageName: String, frame: PageFrame, capturedAt: Long, authorized: () -> Boolean,
): PageWriteResult = withContext(NonCancellable + Dispatchers.IO) {
    outbox.enqueue(packageName, frame, capturedAt, authorized).also {
        if (it.status == PageWriteStatus.SAVED) com.yuyan.imemodule.data.collect.DataCollector.requestSync()
    }
}

/**
 * capture的接受回调只复制引用。即便跨dispatcher返回被取消，finally仍在capture完成清理、
 * 释放物理截图锁之后做不可取消的持久交接；不能把整个取帧流程改成不可取消。
 */
internal suspend fun captureAndPersistAcceptedPage(
    outbox: PageCaptureOutbox, packageName: String, capturedAt: Long, authorized: () -> Boolean,
    capture: suspend (onAccepted: (PageFrame) -> Unit) -> Unit,
): PageWriteResult {
    var accepted: PageFrame? = null
    var result = PageWriteResult(PageWriteStatus.NO_FRAME)
    try {
        capture { frame -> check(accepted == null); accepted = frame }
    } finally {
        accepted?.let { result = persistAcceptedPage(outbox, packageName, it, capturedAt, authorized) }
    }
    return result
}
