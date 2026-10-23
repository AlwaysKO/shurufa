package com.yuyan.imemodule.data.collect

import org.junit.Assert.*
import org.junit.Test

class LocationObservationWatchTest {
    private val wall = 1_800_000_000_000L
    private fun point(second: Long, lat: Double = 23.13) = LocationCandidate(
        lat, 113.3, 30f, wall + second * 1000, "network", second * 1_000_000_000L)
    private fun observe(p: BalancedLocationPolicy, second: Long, lat: Double = 23.13) =
        p.observe(wall + second * 1000, point(second, lat), null, second * 1000)
    private fun stay() = BalancedLocationPolicy().also { p ->
        (1L..181L step 60).forEach { observe(p, it) }
        assertTrue(p.isStationary)
    }
    @Test fun freshStationaryObservationsForHoursDoNotStartGpsOrCreateReports() {
        for (wifi in listOf(false, true)) {
            val p = stay().apply { updateNetwork(wifi) }
            val old = point(181)
            val stored = UploadedLocation(old.latitude, old.longitude, 30f, old.locationTimeMs, old.locationTimeMs, "network")
            for (second in 241L..21_601L step 60) {
                assertTrue(observe(p, second))
                assertEquals(600_000L, p.recoveryDelayMs(second * 1000))
                assertFalse(p.beginRecovery(second * 1000))
                assertTrue(p.isStationary)
                assertFalse(LocationUploadPolicy.shouldUpload(wall + second * 1000, point(second), stored))
            }
        }
    }
    @Test fun noCallbackAfterStayKeepsOneShortOpportunityEveryTenMinutes() {
        val p = stay()
        assertFalse(p.beginRecovery(780_999))
        for (start in 781_000L..4_381_000L step 600_000L) {
            assertTrue(p.beginRecovery(start))
            assertFalse(p.beginRecovery(start))
            assertEquals(120_000L, p.confirmationDelayMs(start))
            p.endConfirmation(start + 120_000)
            assertTrue(p.hasUnconfirmedMotion)
            assertEquals(480_000L, p.recoveryDelayMs(start + 120_000))
        }
    }
    @Test fun repeatedPhoneShakesCannotRefreshTheWindowOrResetTheCooldown() {
        val p = stay()
        assertTrue(p.beginMotionProbe(200_000))
        for (second in 201L..319L) assertFalse(p.beginMotionProbe(second * 1000))
        assertEquals(1_000L, p.confirmationDelayMs(319_000))
        p.endConfirmation(320_000)
        for (second in 321L..799L) assertFalse(p.beginMotionProbe(second * 1000))
        assertEquals(1_000L, p.motionProbeDelayMs(799_000))
        assertTrue(p.beginMotionProbe(800_000))
    }
    @Test fun newlyConfirmedStayDoesNotEraseTheShakeCooldown() {
        val p = stay()
        p.beginMotionProbe(200_000)
        for (second in listOf(210L,250L,290L,330L)) observe(p, second)
        assertFalse(p.hasUnconfirmedMotion)
        assertTrue(p.isStationary)
        assertFalse(p.beginMotionProbe(331_000))
        assertEquals(469_000L, p.motionProbeDelayMs(331_000))
        assertEquals(599_000L, p.recoveryDelayMs(331_000))
    }
    @Test fun realDisplacementRestoresMovingDespiteTheShakeCooldown() {
        val p = stay()
        p.beginMotionProbe(200_000); p.endConfirmation(320_000)
        assertFalse(observe(p, 330, 23.132))
        assertTrue(observe(p, 360, 23.1321))
        assertFalse(p.isStationary)
        assertNull(p.recoveryDelayMs(360_000))
        assertEquals(60_000L,p.intervalMs)
    }
    @Test fun duplicatesCachesAndPoorFixesCannotPostponeTheStaleDeadline() {
        val p = stay()
        val duplicate = point(181)
        assertFalse(p.observe(duplicate.locationTimeMs,duplicate,null,240_000))
        for (second in listOf(300L,400L,500L,600L,700L)) {
            val q = point(second)
            assertFalse(p.observe(q.locationTimeMs,q.copy(knownCached=true),null,second*1000))
            assertFalse(p.observe(q.locationTimeMs,q.copy(accuracyMeters=200f),null,second*1000))
        }
        assertEquals(81_000L,p.recoveryDelayMs(700_000))
        assertTrue(p.beginRecovery(781_000))
    }
    @Test fun selectionDelayedBySleepKeepsTheActualObservationTime() {
        val p = stay()
        assertTrue(p.observe(wall+240_000,point(240),null,1_200_000,240_000))
        assertEquals(0L,p.recoveryDelayMs(1_200_000))
        assertTrue(p.beginRecovery(1_200_000))
    }
    @Test fun missingMonotonicFixClockUsesReceiptClockRatherThanProcessingClock() {
        val p = stay()
        assertTrue(p.observe(wall+260_000,point(240).copy(elapsedRealtimeNanos=0),null,1_200_000,260_000))
        assertEquals(0L,p.recoveryDelayMs(1_200_000))
    }
    @Test fun delayedConfirmationDeadlineDoesNotWasteTheCurrentWake() {
        val p = stay()
        p.beginMotionProbe(200_000)
        p.endConfirmation(1_100_000)
        assertTrue(p.beginRecovery(1_100_000))
        assertFalse(p.beginRecovery(1_100_000))
        assertEquals(120_000L,p.confirmationDelayMs(1_100_000))
    }
}
