package com.yuyan.imemodule.data.collect

import org.junit.Assert.*
import org.junit.Test

class CollectorSyncCadenceTest {
    @Test fun exactRemainingTimeQueryIsReadOnlyAndDoesNotRoundEarlyEligibility() {
        var now=0L
        val cadence=CollectorSyncCadence(300_000L,300_000L) { now }
        assertEquals(0L,cadence.remainingDelay(false))
        assertTrue(cadence.tryStart(true))
        assertEquals(300_000L,cadence.remainingDelay(false))
        now=299_999
        assertEquals(1L,cadence.remainingDelay(true))
        assertEquals(1L,cadence.remainingDelay(false))
        assertFalse(cadence.tryStart(false))
        now=300_000
        assertEquals(0L,cadence.remainingDelay(true))
        assertTrue(cadence.tryStart(false))
        now=0
        assertEquals(0L,cadence.remainingDelay(true))
    }
    @Test fun explicitFiveMinuteCadenceQueriesDoNotConsumeOrResetAttempts() {
        var now=0L
        val cadence=CollectorSyncCadence(300_000L,300_000L) { now }
        repeat(3) { assertEquals(5000L,cadence.wakeDelay(false)) }
        assertTrue(cadence.tryStart(true))
        now=299_999
        assertFalse(cadence.tryStart(false))
        assertEquals(5000L,cadence.wakeDelay(false))
        now=300_000; assertTrue(cadence.tryStart(false))
        // Failed HTTP attempts still occupy a batch; repeated wakeups cannot hammer it.
        repeat(10) { now+=1000; assertFalse(cadence.tryStart(it%2==0)) }
        assertEquals(290_000L,cadence.wakeDelay(true))
    }
    @Test fun explicitSyncBypassesAutomaticDelayButNextAutomaticBatchStillWaits() {
        var now=0L
        val cadence=CollectorSyncCadence { now }
        assertTrue(cadence.tryStart(false))
        now=1000L
        assertFalse(cadence.tryStart(false))
        assertTrue(cadence.tryStart(false,userInitiated=true))
        assertFalse(cadence.tryStart(false))
        assertEquals(900_000L,cadence.wakeDelay(false))
    }
    @Test fun cellularTriggersCoalesceToOneBatchPerFifteenMinutes() {
        var now=0L
        val cadence=CollectorSyncCadence { now }
        var runs=0
        repeat(3600) {
            if(cadence.tryStart(false))runs++
            now+=1000
        }
        assertEquals(4,runs)
    }
    @Test fun newReportsWaitOnlyRemainingTimeAndWifiResumesImmediately() {
        var now=10_000L
        val cadence=CollectorSyncCadence { now }
        assertEquals(5000L,cadence.wakeDelay(false))
        assertTrue(cadence.tryStart(false))
        now+=60_000
        assertEquals(840_000L,cadence.wakeDelay(false))
        assertFalse(cadence.tryStart(false))
        assertEquals(5000L,cadence.wakeDelay(true))
        assertTrue(cadence.tryStart(true))
        now+=1000
        assertFalse(cadence.tryStart(true))
        assertFalse(cadence.tryStart(false))
        now+=900_000
        assertTrue(cadence.tryStart(false))
    }
    @Test fun wifiBatchesAreOneMinuteApart() {
        var now=0L
        val cadence=CollectorSyncCadence { now }
        assertTrue(cadence.tryStart(true))
        now=59_999;assertFalse(cadence.tryStart(true))
        now=60_000;assertTrue(cadence.tryStart(true))
    }
    @Test fun clockResetDoesNotPermanentlyBlockPendingReports() {
        var now=500_000L
        val cadence=CollectorSyncCadence { now }
        assertTrue(cadence.tryStart(false))
        now=0
        assertTrue(cadence.tryStart(false))
    }
}
