package com.yuyan.imemodule.data.redpacket

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SilentPacketDiagnosticsTest {
    @Test fun engineHistoryIsBoundedAndRendersOnlyLast16EnumRecords() {
        val diagnostics = SilentPacketDiagnostics()
        repeat(40) { diagnostics.engine(SilentPacketEngineStage.OCR, SilentPacketEngineEvent.STARTED, it.toLong()) }
        val snapshot = diagnostics.snapshot()
        assertEquals(32, snapshot.engineHistory.size)
        assertEquals(8L, snapshot.engineHistory.first().elapsedMs)
        assertEquals(39L, snapshot.engineHistory.last().elapsedMs)
        val rendered = snapshot.render()
        assertFalse(rendered.contains("OCR STARTED @23ms"))
        assertTrue(rendered.contains("OCR STARTED @24ms"))
        assertTrue(rendered.contains("OCR STARTED @39ms"))
    }

    @Test fun engineSnapshotIsIndependentAndNegativeTimeIsZero() {
        val diagnostics = SilentPacketDiagnostics()
        diagnostics.engine(SilentPacketEngineStage.CAPTURE, SilentPacketEngineEvent.STARTED, -100)
        val first = diagnostics.snapshot()
        diagnostics.engine(SilentPacketEngineStage.OCR, SilentPacketEngineEvent.COMPLETED, 12)
        assertEquals(1, first.engineHistory.size)
        assertEquals(0L, first.engineHistory.single().elapsedMs)
        assertEquals(2, diagnostics.snapshot().engineHistory.size)
    }

    @Test fun engineRecordsDoNotReplaceOtherDiagnosticChannels() {
        val diagnostics = SilentPacketDiagnostics()
        diagnostics.record(SilentPacketDiagnosticEvent.NOTICE_SEEN)
        diagnostics.record(SilentPacketDiagnosticEvent.TASK_STARTED)
        diagnostics.record(SilentPacketDiagnosticEvent.FOREGROUND_READY)
        val before = diagnostics.snapshot()
        diagnostics.engine(SilentPacketEngineStage.OCR, SilentPacketEngineEvent.FRAME_STALE, 1_001)
        val after = diagnostics.snapshot()
        assertEquals(before.lastNotice, after.lastNotice)
        assertEquals(before.lastTask, after.lastTask)
        assertEquals(before.lastRuntime, after.lastRuntime)
        assertEquals(before.history, after.history)
        assertEquals(before.sequence, after.sequence)
        assertEquals(SilentPacketEngineStage.OCR, after.engineHistory.single().stage)
        assertEquals(SilentPacketEngineEvent.FRAME_STALE, after.engineHistory.single().event)
    }

    @Test fun concurrentEngineRecordsRemainBoundedAndSafeToSnapshot() {
        val diagnostics = SilentPacketDiagnostics()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val writers = (0 until 4).map { worker -> pool.submit {
                repeat(100) {
                    diagnostics.engine(SilentPacketEngineStage.CAPTURE, SilentPacketEngineEvent.COMPLETED, worker.toLong())
                    diagnostics.snapshot()
                }
            } }
            writers.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
        assertEquals(32, diagnostics.snapshot().engineHistory.size)
    }

    @Test fun protectsNoticeAndTaskEvidenceFromForegroundStatusChanges() {
        val diagnostics = SilentPacketDiagnostics()
        diagnostics.record(SilentPacketDiagnosticEvent.NOTICE_SEEN, 128, 0)
        diagnostics.record(SilentPacketDiagnosticEvent.PROFILE_REJECTED)
        diagnostics.record(SilentPacketDiagnosticEvent.TASK_START_FAILED)
        diagnostics.record(SilentPacketDiagnosticEvent.FOREGROUND_PROTECTED)
        val snapshot = diagnostics.snapshot()
        assertEquals(SilentPacketDiagnosticEvent.PROFILE_REJECTED, snapshot.lastNotice)
        assertEquals(SilentPacketDiagnosticEvent.TASK_START_FAILED, snapshot.lastTask)
        assertEquals(SilentPacketDiagnosticEvent.FOREGROUND_PROTECTED, snapshot.lastRuntime)
        assertEquals(128, snapshot.selectedUser)
        assertEquals(0, snapshot.noticeUser)
    }

    @Test fun snapshotsAreIndependentAndCountEveryBoundary() {
        val diagnostics = SilentPacketDiagnostics()
        diagnostics.record(SilentPacketDiagnosticEvent.NOTICE_SEEN, 128, 128)
        val old = diagnostics.snapshot()
        diagnostics.record(SilentPacketDiagnosticEvent.PARSED)
        diagnostics.record(SilentPacketDiagnosticEvent.PROFILE_MATCHED)
        diagnostics.record(SilentPacketDiagnosticEvent.GROUP_REJECTED)
        diagnostics.record(SilentPacketDiagnosticEvent.NOTICE_SEEN, -1, 0)
        val current = diagnostics.snapshot()
        assertEquals(1L, old.counters[SilentPacketDiagnosticEvent.NOTICE_SEEN])
        assertEquals(2L, current.counters[SilentPacketDiagnosticEvent.NOTICE_SEEN])
        assertEquals(1L, current.counters[SilentPacketDiagnosticEvent.GROUP_REJECTED])
        assertEquals(-1, current.selectedUser)
        assertEquals(0, current.noticeUser)
        assertEquals(5L, current.sequence)
    }

    @Test fun engineResultsAreMappedToFixedEnumsWithoutRetainingUnknownText() {
        val diagnostics = SilentPacketDiagnostics()
        diagnostics.finished("claimed")
        assertEquals(SilentPacketDiagnosticEvent.TASK_CLAIMED, diagnostics.snapshot().lastTask)
        diagnostics.finished("private group, notification body, secret exception")
        val snapshot = diagnostics.snapshot()
        assertEquals(SilentPacketDiagnosticEvent.TASK_UNKNOWN, snapshot.lastTask)
        assertFalse(snapshot.render().contains("private"))
        assertFalse(snapshot.render().contains("secret"))
    }

    @Test fun simultaneousRecordsDoNotLoseCounts() {
        val diagnostics = SilentPacketDiagnostics()
        val workers = Executors.newFixedThreadPool(4)
        try {
            repeat(4) { workers.submit { repeat(500) { diagnostics.record(SilentPacketDiagnosticEvent.NOTICE_SEEN) } } }
            workers.shutdown()
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
            assertEquals(2000L, diagnostics.snapshot().counters[SilentPacketDiagnosticEvent.NOTICE_SEEN])
            assertEquals(2000L, diagnostics.snapshot().sequence)
        } finally { workers.shutdownNow() }
    }

    @Test fun initialReportIsExplicitlyNoEvidenceAndContainsOnlyAllowedMetadata() {
        val snapshot = SilentPacketDiagnostics().snapshot()
        assertNull(snapshot.lastNotice)
        assertNull(snapshot.lastTask)
        assertNull(snapshot.lastRuntime)
        assertEquals(-1, snapshot.selectedUser)
        assertEquals(-1, snapshot.noticeUser)
        assertTrue(snapshot.render().contains("NOTICE_SEEN=0"))
    }
    @Test fun threeNotificationProfilesRemainSeparateDiagnosticEvidence() {
        val diagnostics = SilentPacketDiagnostics()
        diagnostics.record(SilentPacketDiagnosticEvent.NOTICE_SEEN, selectedUser = 128,
            noticeUser = 0, sbnUser = 128, intentUser = 0)
        diagnostics.record(SilentPacketDiagnosticEvent.PROFILE_REJECTED)
        diagnostics.record(SilentPacketDiagnosticEvent.FOREGROUND_PROTECTED)
        val snapshot = diagnostics.snapshot()
        assertEquals(128, snapshot.selectedUser)
        assertEquals(0, snapshot.noticeUser)
        assertEquals(128, snapshot.sbnUser)
        assertEquals(0, snapshot.intentUser)
        assertTrue(snapshot.render().contains("noticeUser=0 sbnUser=128 intentUser=0"))
        assertEquals(SilentPacketDiagnosticEvent.PROFILE_REJECTED, snapshot.lastNotice)
        assertEquals(1L, snapshot.counters[SilentPacketDiagnosticEvent.PROFILE_REJECTED])
        diagnostics.record(SilentPacketDiagnosticEvent.NOTICE_SEEN, 0, 0, -1, -1)
        val next = diagnostics.snapshot()
        assertEquals(-1, next.sbnUser)
        assertEquals(-1, next.intentUser)
        assertEquals(128, snapshot.sbnUser)
    }
    @Test fun fixedHistoryRetains32EntriesAndRendersOnlyNewest16InOrder() {
        var now = 100L
        val diagnostics = SilentPacketDiagnostics { now++ }
        repeat(40) { diagnostics.record(SilentPacketDiagnosticEvent.NOTICE_SEEN) }
        val snapshot = diagnostics.snapshot()
        assertEquals(32, snapshot.history.size)
        assertEquals(9L, snapshot.history.first().sequence)
        assertEquals(40L, snapshot.history.last().sequence)
        assertEquals(108L, snapshot.history.first().elapsedMs)
        val rendered = snapshot.render()
        assertFalse(rendered.contains("#24@"))
        assertTrue(rendered.contains("#25@124"))
        assertTrue(rendered.contains("#40@139"))
    }

    @Test fun eventHistoryRemainsMonotonicEvenWhenInjectedClockGoesBack() {
        var now = 300L
        val diagnostics = SilentPacketDiagnostics { now }
        diagnostics.record(SilentPacketDiagnosticEvent.INPUT_STOPPED_TOUCH)
        now = 200
        diagnostics.record(SilentPacketDiagnosticEvent.INPUT_STOPPED_TEXT)
        diagnostics.record(SilentPacketDiagnosticEvent.INPUT_STOPPED_CLICK)
        now = 400
        diagnostics.record(SilentPacketDiagnosticEvent.INPUT_STOPPED_SCROLL)
        diagnostics.record(SilentPacketDiagnosticEvent.TASK_START_INVALIDATED)
        diagnostics.record(SilentPacketDiagnosticEvent.TASK_LAUNCH_INVALIDATED)
        val snapshot = diagnostics.snapshot()
        assertEquals(listOf(300L, 300L, 300L, 400L, 400L, 400L), snapshot.history.map { it.elapsedMs })
        assertEquals((1L..6L).toList(), snapshot.history.map { it.sequence })
        assertEquals(SilentPacketDiagnosticEvent.TASK_LAUNCH_INVALIDATED, snapshot.lastTask)
    }

    @Test fun backendReportIsEnumOnlyAndHistoryKeepsEarlierState() {
        val diagnostics = SilentPacketDiagnostics { 100L }
        diagnostics.startedBackend("RUNNING")
        val first = diagnostics.snapshot()
        assertEquals(SilentPacketBackendState.RUNNING, first.lastBackendState)
        assertEquals(SilentPacketBackendState.RUNNING, first.history.last().backendState)
        diagnostics.startedBackend("MAIN_FOCUS_UNKNOWN")
        diagnostics.startedBackend("private title or secret exception")
        val snapshot = diagnostics.snapshot()
        assertEquals(SilentPacketBackendState.UNKNOWN, snapshot.lastBackendState)
        assertEquals(SilentPacketBackendState.RUNNING, snapshot.history.first().backendState)
        assertEquals(SilentPacketBackendState.MAIN_FOCUS_UNKNOWN, snapshot.history[1].backendState)
        assertFalse(snapshot.render().contains("private"))
        assertFalse(snapshot.render().contains("secret"))
        assertEquals(1, first.history.size)
    }
}
