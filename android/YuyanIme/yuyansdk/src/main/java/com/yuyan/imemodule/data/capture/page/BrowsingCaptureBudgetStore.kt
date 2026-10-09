package com.yuyan.imemodule.data.capture.page

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import java.io.File

internal enum class BrowseBudgetResult { ALLOWED, INTERVAL, HOUR_LIMIT, DAY_LIMIT, INVALID_CLOCK }

/**
 * 每台设备微信/抖音普通浏览共用的持久尝试预算；不存页面内容，不管理聊天。视频首帧使用独立账本，每秒最多一次、每小时60次、每天300次。
 * 必须后台调用，预留先于物理取图，失败/重复/导航失效不退款。库损坏/写失败向上抛错，禁止清库放行。
 * 同boot用单调时间；跨boot只计已知新启动时间，不凭墙钟估算关机时间，额度恢复可能偏保守。
 */
internal class BrowsingCaptureBudgetStore(context: Context, private val videoFrames: Boolean = false) : SQLiteOpenHelper(
    context.applicationContext, File(context.noBackupFilesDir, if (videoFrames) "video_frame_budget.db" else "browse_capture_budget.db").absolutePath, null, 1,
) {
    private val app = context.applicationContext
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE budget_clock (id INTEGER PRIMARY KEY CHECK(id=1), boot INTEGER NOT NULL CHECK(boot>=0), elapsed INTEGER NOT NULL CHECK(elapsed>=0), logical INTEGER NOT NULL CHECK(logical>=0))")
        db.execSQL("CREATE TABLE attempts (at INTEGER PRIMARY KEY CHECK(at>=0))")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Unsupported browse budget schema upgrade")
    }

    /** 无boot证据时明确拒绝；不能用当前进程随机ID冒充设备启动标识。 */
    private fun bootNow(): Int = runCatching {
        if (Build.VERSION.SDK_INT >= 24) Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, -1) else -1
    }.getOrDefault(-1)

    fun reserveNow(): BrowseBudgetResult = reserve(bootNow(), SystemClock.elapsedRealtime())

    /** 仅查询事件候选需要再等多久；推进安全时钟账本，但不预留尝试、不退款。 */
    fun remainingIntervalNow(): Long? = remainingInterval(bootNow(), SystemClock.elapsedRealtime())
    fun remainingInterval(boot: Int, elapsed: Long): Long? = evaluate(boot, elapsed, spend = false).let {
        if (it.result == BrowseBudgetResult.INVALID_CLOCK) null else it.remaining
    }
    fun reserve(boot: Int, elapsed: Long): BrowseBudgetResult = evaluate(boot, elapsed, spend = true).result

    private data class Availability(val result: BrowseBudgetResult, val remaining: Long = 0)
    @Synchronized private fun evaluate(boot: Int, elapsed: Long, spend: Boolean): Availability {
        if (boot < 0 || elapsed < 0) return Availability(BrowseBudgetResult.INVALID_CLOCK)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val old = db.rawQuery("SELECT boot,elapsed,logical FROM budget_clock WHERE id=1", null).use {
                if (it.moveToFirst()) Clock(it.getInt(0), it.getLong(1), it.getLong(2)) else null
            }
            val logical = if (old == null) {
                check(db.rawQuery("SELECT COUNT(*) FROM attempts", null).use { it.moveToFirst(); it.getLong(0) == 0L })
                0L
            } else {
                if (boot < old.boot || (boot == old.boot && elapsed < old.elapsed)) return Availability(BrowseBudgetResult.INVALID_CLOCK)
                val delta = if (boot == old.boot) elapsed - old.elapsed else elapsed
                if (old.logical > Long.MAX_VALUE - delta) return Availability(BrowseBudgetResult.INVALID_CLOCK)
                old.logical + delta
            }
            db.execSQL("INSERT OR REPLACE INTO budget_clock(id,boot,elapsed,logical) VALUES(1,?,?,?)", arrayOf(boot, elapsed, logical))
            db.execSQL("DELETE FROM attempts WHERE at<=?", arrayOf(logical - DAY))
            val usage = db.rawQuery("SELECT COUNT(*), COALESCE(SUM(CASE WHEN at>? THEN 1 ELSE 0 END),0), MAX(at) FROM attempts",
                arrayOf((logical - HOUR).toString())).use {
                it.moveToFirst()
                Usage(it.getInt(0), it.getInt(1), if (it.isNull(2)) null else it.getLong(2))
            }
            val result = when {
                usage.day >= if (videoFrames) 300 else 100 -> BrowseBudgetResult.DAY_LIMIT
                usage.hour >= if (videoFrames) 60 else 20 -> BrowseBudgetResult.HOUR_LIMIT
                usage.last?.let { logical - it < interval } == true -> BrowseBudgetResult.INTERVAL
                else -> BrowseBudgetResult.ALLOWED
            }
            if (spend && result == BrowseBudgetResult.ALLOWED) db.execSQL("INSERT INTO attempts(at) VALUES(?)", arrayOf(logical))
            db.setTransactionSuccessful()
            val remaining = if (result == BrowseBudgetResult.INTERVAL) interval - (logical - requireNotNull(usage.last)) else 0L
            return Availability(result, remaining)
        } finally { db.endTransaction() }
    }
    private val interval: Long get() = if (videoFrames) 1_000L else INTERVAL
    private data class Clock(val boot: Int, val elapsed: Long, val logical: Long)
    private data class Usage(val day: Int, val hour: Int, val last: Long?)
    private companion object {
        const val INTERVAL = 180_000L
        const val HOUR = 3_600_000L
        const val DAY = 86_400_000L
    }
}
