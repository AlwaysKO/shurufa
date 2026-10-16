package com.yuyan.imemodule.data.collect

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.location.Location
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** 固定阶段，不接受页面文字、坐标、金额、OCR或异常消息。仅本地排障，不参与上传。 */
internal enum class CaptureHistoryEvent {
    LOCATION_REQUESTED, LOCATION_REQUEST_FAILED, LOCATION_RECEIVED, LOCATION_SELECTED,
    LOCATION_GUARD_REJECTED, LOCATION_CLOCK_ROLLBACK, LOCATION_QUALITY_REJECTED,
    LOCATION_CADENCE_REJECTED, LOCATION_SAVED, LOCATION_ENQUEUE_FAILED, LOCATION_STOPPED,
    LOCATION_GPS_IDLE, LOCATION_GPS_RETRY_SCHEDULED,
    LOCATION_CACHE_REJECTED,
    PAGE_SLOT_BUSY, PAGE_SCOPE_LOST, PAGE_BACKGROUND_PAUSED, PAGE_CANCELLED,
    PAGE_BUDGET_ALLOWED, PAGE_NO_FRAME, PAGE_SAVED, PAGE_DUPLICATE, PAGE_FAILED, HISTORY_DROPPED,
    CHAT_SEND_REQUESTED, CHAT_PROMPT_STARTED, CHAT_PROMPT_RENDER_READY, CHAT_PROMPT_RENDER_UNSETTLED,
    CHAT_PROMPT_FRAME_ALLOWED, CHAT_PROMPT_FRAME_BLOCKED, CHAT_FRAME_READY, CHAT_FRAME_FAILED,
    CHAT_SCREENSHOT_CANCELLED, CHAT_IDENTITY_REJECTED, CHAT_CONTENT_DUPLICATE,
    CHAT_PERSIST_INSERTED, CHAT_PERSIST_DUPLICATE, CHAT_PERSIST_FAILED,
    CHAT_UPLOAD_ACKNOWLEDGED, CHAT_UPLOAD_FAILED, CHAT_PROMPT_CANCELLED,
}
internal data class CaptureHistoryEntry(
    val at: Long, val event: CaptureHistoryEvent,
    val fixAt: Long? = null, val receivedAt: Long? = null,
    val provider: String? = null, val accuracy: Float? = null, val intervalMs: Long? = null, val count: Int? = null,
)

/** 所有调用由 IO actor 执行；容量与字段长度同时有界，SQLite 复用已释放页。 */
internal class CaptureHistoryStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext, File(context.noBackupFilesDir, "capture_history.db").absolutePath, null, 1,
), java.io.Closeable {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY,at INTEGER NOT NULL,event TEXT NOT NULL,fix_at INTEGER,received_at INTEGER,provider TEXT,accuracy REAL,interval_ms INTEGER,count INTEGER)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    fun append(entries: List<CaptureHistoryEntry>, now: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (entry in entries.takeLast(MAX_ROWS)) {
                val provider = entry.provider?.let { if (it in setOf("gps", "network", "fused", "passive")) it else "other" }
                db.execSQL("INSERT INTO events(at,event,fix_at,received_at,provider,accuracy,interval_ms,count) VALUES(?,?,?,?,?,?,?,?)",
                    arrayOf<Any?>(entry.at, entry.event.name, entry.fixAt, entry.receivedAt, provider,
                        entry.accuracy?.takeIf { it.isFinite() && it >= 0 }, entry.intervalMs, entry.count))
            }
            prune(db, now)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    private fun prune(db: SQLiteDatabase, now: Long) {
        db.delete("events", "at<?", arrayOf((now - RETENTION_MS).toString()))
        db.execSQL("DELETE FROM events WHERE id NOT IN (SELECT id FROM events ORDER BY id DESC LIMIT $MAX_ROWS)")
    }
    fun recent(now: Long): List<CaptureHistoryEntry> {
        val db = writableDatabase
        prune(db, now)
        return db.rawQuery("SELECT at,event,fix_at,received_at,provider,accuracy,interval_ms,count FROM events ORDER BY id",null).use { c ->
            fun long(index: Int): Long? = if (c.isNull(index)) null else c.getLong(index)
            buildList { while (c.moveToNext()) add(CaptureHistoryEntry(c.getLong(0), CaptureHistoryEvent.valueOf(c.getString(1)),
                long(2),long(3),c.getString(4),if (c.isNull(5)) null else c.getFloat(5),long(6), if (c.isNull(7)) null else c.getInt(7))) }
        }
    }
    companion object { const val MAX_ROWS = 4096; const val RETENTION_MS = 48 * 60 * 60 * 1000L }
}

/** 有界异步合批，可注入写入端验证功耗/取消边界；不因失败启动额外定时重试。 */
internal class CaptureHistoryWriter(
    scope: CoroutineScope,
    private val authorized: (Long) -> Boolean,
    private val backgroundAllowed: () -> Boolean,
    private val pause: suspend () -> Unit = { delay(5000) },
    private val write: suspend (List<CaptureHistoryEntry>) -> Unit,
) {
    private data class Pending(val entry: CaptureHistoryEntry, val epoch: Long)
    private val dropped = java.util.concurrent.atomic.AtomicInteger()
    private val pending = Channel<Pending>(128, BufferOverflow.DROP_OLDEST,
        onUndeliveredElement = { dropped.incrementAndGet() })
    private val consumer = scope.launch {
            for (first in pending) {
                pause()
                val batch = mutableListOf(first)
                repeat(127) { pending.tryReceive().getOrNull()?.let { batch.add(it) } }
                val entries = batch.filter { authorized(it.epoch) }.map { it.entry }
                if (entries.isEmpty()) continue
                if (!backgroundAllowed()) { dropped.addAndGet(entries.size); continue }
                val lost = dropped.getAndSet(0)
                val outgoing = if (lost == 0) entries else entries + CaptureHistoryEntry(
                    System.currentTimeMillis(), CaptureHistoryEvent.HISTORY_DROPPED, count = lost)
                try { write(outgoing) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { dropped.addAndGet(entries.size + lost) }
            }
    }
    fun record(entry: CaptureHistoryEntry, epoch: Long) { pending.trySend(Pending(entry, epoch)) }
    fun close() { pending.cancel(); consumer.cancel() }
}

/** 最多128条待写；5秒合批只使用协程延迟，无 Alarm/WakeLock/网络，熄屏可能延后。 */
internal object CaptureHistory {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var writer: CaptureHistoryWriter? = null
    @Synchronized private fun writer(context: Context, diagnosticToken: Long): CaptureHistoryWriter {
        writer?.let { return it }
        val app = context.applicationContext
        return CaptureHistoryWriter(scope,
            authorized = { it == CollectionConsent.epoch && CollectionConsent.enabled(app) &&
                CollectionDiagnostics.token(app) == diagnosticToken },
            backgroundAllowed = ImageUploadRuntime::isBackgroundWorkAllowed,
            write = { entries -> CollectionDiagnostics.writeIfCurrent(app, diagnosticToken) {
                CaptureHistoryStore(app).use { it.append(entries, System.currentTimeMillis()) }
            }; Unit },
        ).also { writer = it }
    }
    fun record(context: Context, event: CaptureHistoryEvent, location: Location? = null,
        receivedAt: Long? = null, intervalMs: Long? = null, provider: String? = location?.provider) {
        CollectionDiagnostics.whenEnabled(context) { diagnosticToken ->
            val entry = CaptureHistoryEntry(System.currentTimeMillis(), event, location?.time, receivedAt,
                provider, location?.takeIf { it.hasAccuracy() }?.accuracy, intervalMs)
            writer(context, diagnosticToken).record(entry, CollectionConsent.epoch)
        }
    }
    @Synchronized fun cancelPending() { writer?.close(); writer = null }
}
