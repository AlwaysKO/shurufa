package com.yuyan.imemodule.utils

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28])
class LogRetentionTest {
    private lateinit var app: Application
    private lateinit var logs: File
    private val now = 1_800_000_000_000L
    private val day = 24 * 60 * 60 * 1000L

    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("log_retention", 0).edit().clear().commit()
        logs = File(app.filesDir, "crash_logs")
        if (Files.isSymbolicLink(logs.toPath())) logs.delete() else logs.deleteRecursively()
        logs.mkdirs()
    }

    private fun log(name: String, modified: Long) = File(logs, name).apply {
        writeText("test-log")
        assertTrue(setLastModified(modified))
    }

    @Test fun deletesOnlyStrictlyOlderThanThirtyDaysCrashLogs() {
        val old = log("crash-old.log", now - 30 * day - 1)
        val boundary = log("crash-boundary.log", now - 30 * day)
        val fresh = log("crash-fresh.log", now - day)
        val future = log("crash-future.log", now + day)
        val unknownTime = log("crash-zero.log", 0)
        val business = log("business-pending.log", now - 60 * day)
        val other = log("crash-old.bin", now - 60 * day)
        val nested = File(logs, "nested").apply { mkdirs() }
        val nestedLog = File(nested, "crash-old.log").apply { writeText("nested"); setLastModified(now - 60 * day) }
        LogRetention.prune(app, now)
        assertFalse(old.exists())
        listOf(boundary, fresh, future, unknownTime, business, other, nestedLog).forEach { assertTrue(it.name, it.exists()) }
    }

    @Test fun scansAtMostOncePerDayAndRescansAfterClockRollback() {
        LogRetention.prune(app, now)
        val old = log("crash-late.log", now - 60 * day)
        LogRetention.prune(app, now + day - 1)
        assertTrue(old.exists())
        LogRetention.prune(app, now + day)
        assertFalse(old.exists())
        val afterRollback = log("crash-rollback.log", now - 60 * day)
        LogRetention.prune(app, now)
        assertFalse(afterRollback.exists())
    }

    @Test fun doesNotFollowFileSymlinksOutsideTheLogDirectory() {
        val outside = File(app.filesDir, "outside-log-sentinel").apply {
            writeText("must-survive"); setLastModified(now - 60 * day)
        }
        val link = File(logs, "crash-link.log")
        try {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            LogRetention.prune(app, now)
            assertTrue(Files.isSymbolicLink(link.toPath()))
            assertEquals("must-survive", outside.readText())
        } finally { link.delete(); outside.delete() }
    }

    @Test fun doesNotScanASymlinkedLogDirectory() {
        logs.delete()
        val outside = File(app.filesDir, "outside-logs").apply { mkdirs() }
        val sentinel = File(outside, "crash-old.log").apply { writeText("must-survive"); setLastModified(now - 60 * day) }
        try {
            Files.createSymbolicLink(logs.toPath(), outside.toPath())
            LogRetention.prune(app, now)
            assertEquals("must-survive", sentinel.readText())
        } finally { logs.delete(); sentinel.delete(); outside.delete() }
    }
}
