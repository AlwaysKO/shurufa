package com.yuyan.imemodule.data.capture.page

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class BrowsingCaptureBudgetStoreTest {
    private lateinit var app: Application
    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        app.deleteDatabase(File(app.noBackupFilesDir, "browse_capture_budget.db").absolutePath)
    }
    @Test fun videoFramesHaveIndependentPersistentBudgetAndDoNotWaitThreeMinutes() {
        val videoDb = File(app.noBackupFilesDir, "video_frame_budget.db").absolutePath
        app.deleteDatabase(videoDb)
        BrowsingCaptureBudgetStore(app).use { assertEquals(BrowseBudgetResult.ALLOWED, it.reserve(4, 1000)) }
        BrowsingCaptureBudgetStore(app, videoFrames = true).use {
            assertEquals(BrowseBudgetResult.ALLOWED, it.reserve(4, 1000))
            assertEquals(BrowseBudgetResult.INTERVAL, it.reserve(4, 1999))
            assertEquals(BrowseBudgetResult.ALLOWED, it.reserve(4, 2000))
        }
        BrowsingCaptureBudgetStore(app, videoFrames = true).use {
            assertEquals(BrowseBudgetResult.INTERVAL, it.reserve(4, 2999))
            assertEquals(BrowseBudgetResult.ALLOWED, it.reserve(4, 3000))
        }
        app.deleteDatabase(videoDb)
    }

    @Test fun paymentAttemptsAreIndependentOfOrdinaryAndVideoLimitsAndSurviveReopen() {
        app.deleteDatabase(File(app.noBackupFilesDir, "payment_capture_budget.db").absolutePath)
        app.deleteDatabase(File(app.noBackupFilesDir, "video_frame_budget.db").absolutePath)
        BrowsingCaptureBudgetStore(app).use { s ->
            repeat(100) { assertEquals(BrowseBudgetResult.ALLOWED, s.reserve(4, it * 180000L)) }
            assertEquals(BrowseBudgetResult.DAY_LIMIT, s.reserve(4, 18000000))
        }
        BrowsingCaptureBudgetStore(app, videoFrames = true).use { s ->
            repeat(60) { assertEquals(BrowseBudgetResult.ALLOWED, s.reserve(4, 18000000L + it * 1000)) }
            assertEquals(BrowseBudgetResult.HOUR_LIMIT, s.reserve(4, 18060000))
        }
        BrowsingCaptureBudgetStore(app, paymentPages = true).use { s ->
            assertEquals(BrowseBudgetResult.ALLOWED, s.reserve(4, 18060000))
            assertEquals(BrowseBudgetResult.INTERVAL, s.reserve(4, 18060999))
        }
        BrowsingCaptureBudgetStore(app, paymentPages = true).use { s ->
            assertEquals(1L, s.remainingInterval(4, 18060999))
            assertEquals(BrowseBudgetResult.ALLOWED, s.reserve(4, 18061000))
        }
    }
    @Test fun paymentLimitsRemainBoundedAcrossRestartAndReboot() {
        app.deleteDatabase(File(app.noBackupFilesDir, "payment_capture_budget.db").absolutePath)
        BrowsingCaptureBudgetStore(app, paymentPages = true).use { s ->
            repeat(5) { hour ->
                repeat(60) { n -> assertEquals(BrowseBudgetResult.ALLOWED, s.reserve(4, hour * 3600000L + n * 1000)) }
                assertEquals(if (hour == 4) BrowseBudgetResult.DAY_LIMIT else BrowseBudgetResult.HOUR_LIMIT,
                    s.reserve(4, hour * 3600000L + 60000))
            }
        }
        BrowsingCaptureBudgetStore(app, paymentPages = true).use { s ->
            assertEquals(BrowseBudgetResult.DAY_LIMIT, s.reserve(5, 1000))
            assertEquals(BrowseBudgetResult.INVALID_CLOCK, s.reserve(4, 99999999))
        }
    }

    @Test fun firstAttemptAndExactIntervalAcrossReopen() {
        BrowsingCaptureBudgetStore(app).use { assertEquals(BrowseBudgetResult.ALLOWED, it.reserve(4, 1000)) }
        BrowsingCaptureBudgetStore(app).use {
            assertEquals(BrowseBudgetResult.INTERVAL, it.reserve(4, 180999))
            assertEquals(BrowseBudgetResult.ALLOWED, it.reserve(4, 181000))
        }
    }
    @Test fun intervalQuerySurvivesReopenAndDoesNotSpendOrRefundAnAttempt() {
        BrowsingCaptureBudgetStore(app).use { assertEquals(BrowseBudgetResult.ALLOWED, it.reserve(4, 1000)) }
        BrowsingCaptureBudgetStore(app).use {
            assertEquals(179000L, it.remainingInterval(4, 2000))
            assertEquals(1L, it.remainingInterval(4, 180999))
            assertEquals(0L, it.remainingInterval(4, 181000))
            assertEquals(BrowseBudgetResult.ALLOWED, it.reserve(4, 181000))
            assertEquals(180000L, it.remainingInterval(4, 181000))
            assertNull(it.remainingInterval(4, 180999))
        }
    }
    @Test fun intervalQueryPreservesHourAndDayLimitsAndRebootClock() {
        BrowsingCaptureBudgetStore(app).use { s ->
            assertNull(s.remainingInterval(-1, 0))
            repeat(100) { assertEquals(BrowseBudgetResult.ALLOWED, s.reserve(4, it * 180000L)) }
            assertEquals(0L, s.remainingInterval(4, 18000000))
            assertEquals(BrowseBudgetResult.DAY_LIMIT, s.reserve(4, 18000000))
        }
        setup()
        BrowsingCaptureBudgetStore(app).use { s ->
            assertEquals(BrowseBudgetResult.ALLOWED, s.reserve(4, 999999))
            assertEquals(179000L, s.remainingInterval(5, 1000))
            assertEquals(BrowseBudgetResult.INTERVAL, s.reserve(5, 1000))
        }
    }
    @Test fun hourLimitAndBoundaryAreRollingNotCalendarBuckets() {
        BrowsingCaptureBudgetStore(app).use { s ->
            repeat(20) { assertEquals(BrowseBudgetResult.ALLOWED, s.reserve(1, it * 180000L)) }
            assertEquals(BrowseBudgetResult.HOUR_LIMIT, s.reserve(1, 3599999))
            assertEquals(BrowseBudgetResult.ALLOWED, s.reserve(1, 3600000))
        }
    }
    @Test fun dayLimitSurvivesReopenAndExpiresAtExactBoundary() {
        BrowsingCaptureBudgetStore(app).use { s ->
            repeat(100) { assertEquals(BrowseBudgetResult.ALLOWED, s.reserve(1, it * 180000L)) }
            assertEquals(BrowseBudgetResult.DAY_LIMIT, s.reserve(1, 18000000))
        }
        BrowsingCaptureBudgetStore(app).use {
            assertEquals(BrowseBudgetResult.DAY_LIMIT, it.reserve(1, 86399999))
            assertEquals(BrowseBudgetResult.ALLOWED, it.reserve(1, 86400000))
        }
    }
    @Test fun rebootDoesNotClearBudgetOrAssumeShutdownDuration() {
        BrowsingCaptureBudgetStore(app).use { s ->
            assertEquals(BrowseBudgetResult.ALLOWED, s.reserve(4, 10000000))
            assertEquals(BrowseBudgetResult.INTERVAL, s.reserve(5, 1000))
            assertEquals(BrowseBudgetResult.ALLOWED, s.reserve(5, 180000))
            assertEquals(BrowseBudgetResult.INVALID_CLOCK, s.reserve(4, 99999999))
        }
    }
    @Test fun missingOrReversedClockFailsClosedWithoutResettingQuota() {
        BrowsingCaptureBudgetStore(app).use { s ->
            assertEquals(BrowseBudgetResult.INVALID_CLOCK, s.reserve(-1, 1000))
            assertEquals(BrowseBudgetResult.INVALID_CLOCK, s.reserve(1, -1))
            assertEquals(BrowseBudgetResult.ALLOWED, s.reserve(1, 1000))
            assertEquals(BrowseBudgetResult.INVALID_CLOCK, s.reserve(1, 999))
            assertEquals(BrowseBudgetResult.INTERVAL, s.reserve(1, 180999))
        }
    }
    @Test fun failedOrDuplicateImageCannotRefundAnAttempt() {
        BrowsingCaptureBudgetStore(app).use { s ->
            assertEquals(BrowseBudgetResult.ALLOWED, s.reserve(1, 1000))
            // 没有退款API：截图失败、重复、导航失效仍已消耗预留。
            assertEquals(BrowseBudgetResult.INTERVAL, s.reserve(1, 1001))
        }
    }
    @Test fun storageFailureRollsBackClockAndAttemptTogether() {
        BrowsingCaptureBudgetStore(app).use { s ->
            s.writableDatabase.execSQL("CREATE TRIGGER fail_reserve BEFORE INSERT ON attempts BEGIN SELECT RAISE(ABORT, 'disk failure'); END")
            assertThrows(Exception::class.java) { s.reserve(1, 1000) }
            s.writableDatabase.execSQL("DROP TRIGGER fail_reserve")
            assertEquals(BrowseBudgetResult.ALLOWED, s.reserve(1, 999))
        }
    }
    @Test fun twoStoreInstancesShareOneDeviceQuota() {
        BrowsingCaptureBudgetStore(app).use { a -> BrowsingCaptureBudgetStore(app).use { b ->
            assertEquals(BrowseBudgetResult.ALLOWED, a.reserve(1, 1000))
            assertEquals(BrowseBudgetResult.INTERVAL, b.reserve(1, 1000))
            assertEquals(BrowseBudgetResult.ALLOWED, b.reserve(1, 181000))
            assertEquals(BrowseBudgetResult.INTERVAL, a.reserve(1, 181000))
        } }
    }
    @Test fun concurrentInstancesCannotBothSpendTheSameInterval() {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            BrowsingCaptureBudgetStore(app).use { a -> BrowsingCaptureBudgetStore(app).use { b ->
                a.writableDatabase; b.writableDatabase
                val start = java.util.concurrent.CountDownLatch(1)
                val futures = listOf(a, b).map { store -> pool.submit<BrowseBudgetResult> {
                    check(start.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    store.reserve(1, 1000, "com.tencent.mm")
                } }
                start.countDown()
                val results = futures.map { it.get(10, java.util.concurrent.TimeUnit.SECONDS) }
                assertEquals(1, results.count { it == BrowseBudgetResult.ALLOWED })
                assertEquals(1, results.count { it == BrowseBudgetResult.INTERVAL })
            } }
        } finally { pool.shutdownNow() }
    }
    @Test fun systemEntryUsesBootEvidenceAndMonotonicTime() {
        android.provider.Settings.Global.putInt(app.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1)
        BrowsingCaptureBudgetStore(app).use { s ->
            assertEquals(BrowseBudgetResult.INVALID_CLOCK, s.reserveNow())
            android.provider.Settings.Global.putInt(app.contentResolver, android.provider.Settings.Global.BOOT_COUNT, 3)
            assertEquals(BrowseBudgetResult.ALLOWED, s.reserveNow())
            assertEquals(BrowseBudgetResult.INTERVAL, s.reserveNow())
            android.os.SystemClock.sleep(180000)
            assertEquals(BrowseBudgetResult.ALLOWED, s.reserveNow())
        }
    }
    @Test fun missingClockWithExistingUsageMustNotResetBudget() {
        BrowsingCaptureBudgetStore(app).use { s ->
            assertEquals(BrowseBudgetResult.ALLOWED, s.reserve(1, 1000))
            s.writableDatabase.execSQL("DELETE FROM budget_clock")
            assertThrows(IllegalStateException::class.java) { s.reserve(1, 1001) }
            s.readableDatabase.rawQuery("SELECT COUNT(*) FROM attempts", null).use {
                assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
            }
        }
    }
    @Test fun rebootDoesNotEraseFullDayBudget() {
        BrowsingCaptureBudgetStore(app).use { s ->
            repeat(100) { assertEquals(BrowseBudgetResult.ALLOWED, s.reserve(1, it * 180000L)) }
        }
        BrowsingCaptureBudgetStore(app).use { s ->
            assertEquals(BrowseBudgetResult.DAY_LIMIT, s.reserve(2, 1000))
        }
    }
    @Test fun logicalClockOverflowCannotResetBudget() {
        BrowsingCaptureBudgetStore(app).use { s ->
            assertEquals(BrowseBudgetResult.ALLOWED, s.reserve(1, 0))
            assertEquals(BrowseBudgetResult.ALLOWED, s.reserve(1, Long.MAX_VALUE))
            assertEquals(BrowseBudgetResult.INVALID_CLOCK, s.reserve(2, 1))
        }
    }
    @Test fun recreatedScheduleStillRequiresPersistentInterval() {
        val page = BrowsePageToken("com.tencent.mm", 1, 1)
        BrowsingCaptureBudgetStore(app).use { store ->
            val first = BrowsingCaptureSchedule().apply { changed(page, 0) }
            assertEquals(page, first.take(800, page, true) { store.reserve(1, 800) == BrowseBudgetResult.ALLOWED })
        }
        BrowsingCaptureBudgetStore(app).use { store ->
            val restarted = BrowsingCaptureSchedule().apply { changed(page, 801) }
            assertNull(restarted.take(1601, page, true) { store.reserve(1, 1601) == BrowseBudgetResult.ALLOWED })
            restarted.changed(page, 180000)
            assertEquals(page, restarted.take(180800, page, true) { store.reserve(1, 180800) == BrowseBudgetResult.ALLOWED })
        }
    }
    private val wechat = "com.tencent.mm"
    private val douyin = "com.ss.android.ugc.aweme"

    @Test fun ordinaryIntervalsArePerAppAndSurviveRestart() {
        BrowsingCaptureBudgetStore(app).use { s ->
            assertEquals(BrowseBudgetResult.ALLOWED, s.reserve(4, 1000, wechat))
            assertEquals(0L, s.remainingInterval(4, 1000, douyin))
            assertEquals(BrowseBudgetResult.ALLOWED, s.reserve(4, 1000, douyin))
        }
        BrowsingCaptureBudgetStore(app).use { s ->
            assertEquals(179000L, s.remainingInterval(4, 2000, wechat))
            assertEquals(179000L, s.remainingInterval(4, 2000, douyin))
            assertEquals(BrowseBudgetResult.INTERVAL, s.reserve(4, 180999, wechat))
            assertEquals(BrowseBudgetResult.ALLOWED, s.reserve(4, 181000, wechat))
            assertEquals(BrowseBudgetResult.ALLOWED, s.reserve(4, 181000, douyin))
            s.readableDatabase.rawQuery("SELECT COUNT(*) FROM attempts", null).use {
                assertTrue(it.moveToFirst()); assertEquals(4, it.getInt(0))
            }
        }
    }

    @Test fun ordinaryHourAndDayLimitsRemainSharedAcrossApps() {
        BrowsingCaptureBudgetStore(app).use { s ->
            repeat(5) { hour ->
                repeat(10) { n ->
                    val time = hour * 3600000L + n * 180000L
                    assertEquals(BrowseBudgetResult.ALLOWED, s.reserve(4, time, wechat))
                    assertEquals(BrowseBudgetResult.ALLOWED, s.reserve(4, time, douyin))
                }
                val expected = if (hour == 4) BrowseBudgetResult.DAY_LIMIT else BrowseBudgetResult.HOUR_LIMIT
                assertEquals(expected, s.reserve(4, hour * 3600000L + 1800000, wechat))
                assertEquals(expected, s.reserve(4, hour * 3600000L + 1800000, douyin))
            }
        }
    }

    private fun createLegacyBudget(attempts: Int) {
        val file = File(app.noBackupFilesDir, "browse_capture_budget.db")
        file.parentFile!!.mkdirs()
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE budget_clock (id INTEGER PRIMARY KEY CHECK(id=1), boot INTEGER NOT NULL, elapsed INTEGER NOT NULL, logical INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE attempts (at INTEGER PRIMARY KEY CHECK(at>=0))")
            repeat(attempts) { db.execSQL("INSERT INTO attempts(at) VALUES(?)", arrayOf(it * 180000L)) }
            db.execSQL("INSERT INTO budget_clock VALUES(1,4,1000,?)", arrayOf((attempts - 1) * 180000L))
            db.version = 1
        }
    }

    @Test fun legacyUpgradeRetainsUnknownAppCooldownWithoutPermanentlySharingNewAttempts() {
        createLegacyBudget(1)
        BrowsingCaptureBudgetStore(app).use { s ->
            assertEquals(179000L, s.remainingInterval(4, 2000, wechat))
            assertEquals(179000L, s.remainingInterval(4, 2000, douyin))
            assertEquals(BrowseBudgetResult.INTERVAL, s.reserve(4, 180999, douyin))
            assertEquals(BrowseBudgetResult.ALLOWED, s.reserve(4, 181000, wechat))
            assertEquals(BrowseBudgetResult.ALLOWED, s.reserve(4, 181000, douyin))
            assertEquals(2, s.readableDatabase.version)
        }
    }

    @Test fun legacyUpgradeDoesNotResetTotalUsage() {
        createLegacyBudget(100)
        BrowsingCaptureBudgetStore(app).use { s ->
            assertEquals(BrowseBudgetResult.DAY_LIMIT, s.reserve(4, 181000, wechat))
            assertEquals(BrowseBudgetResult.DAY_LIMIT, s.reserve(4, 181000, douyin))
            s.readableDatabase.rawQuery("SELECT COUNT(*) FROM attempts", null).use {
                assertTrue(it.moveToFirst()); assertEquals(100, it.getInt(0))
            }
        }
    }

    @Test fun concurrentDifferentAppsCanSpendSameMillisecondButBothCount() {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            BrowsingCaptureBudgetStore(app).use { a -> BrowsingCaptureBudgetStore(app).use { b ->
                a.writableDatabase; b.writableDatabase
                val start = java.util.concurrent.CountDownLatch(1)
                val futures = listOf(a to wechat, b to douyin).map { (store, pkg) -> pool.submit<BrowseBudgetResult> {
                    check(start.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    store.reserve(1, 1000, pkg)
                } }
                start.countDown()
                assertEquals(listOf(BrowseBudgetResult.ALLOWED, BrowseBudgetResult.ALLOWED),
                    futures.map { it.get(10, java.util.concurrent.TimeUnit.SECONDS) })
                a.readableDatabase.rawQuery("SELECT COUNT(*) FROM attempts", null).use {
                    assertTrue(it.moveToFirst()); assertEquals(2, it.getInt(0))
                }
            } }
        } finally { pool.shutdownNow() }
    }

    @Test fun perAppRebootCooldownOnlyCountsKnownElapsedTime() {
        BrowsingCaptureBudgetStore(app).use { s ->
            assertEquals(BrowseBudgetResult.ALLOWED, s.reserve(4, 10000000, wechat))
            assertEquals(BrowseBudgetResult.ALLOWED, s.reserve(4, 10010000, douyin))
            assertEquals(169000L, s.remainingInterval(5, 1000, wechat))
            assertEquals(179000L, s.remainingInterval(5, 1000, douyin))
        }
    }
}
