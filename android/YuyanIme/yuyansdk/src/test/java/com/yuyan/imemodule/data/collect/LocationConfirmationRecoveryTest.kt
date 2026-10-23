package com.yuyan.imemodule.data.collect

import org.junit.Assert.*
import org.junit.Test

class LocationConfirmationRecoveryTest {
    private val start = 1_800_000_000_000L
    private fun point(seconds: Long, lat: Double = 23.13) =
        LocationCandidate(lat, 113.3, 30f, start + seconds * 1000, "network", seconds * 1_000_000_000L)
    private fun observe(policy: BalancedLocationPolicy, seconds: Long, lat: Double = 23.13) =
        policy.observe(start + seconds * 1000, point(seconds, lat), null, seconds * 1000)
    private fun probe(): BalancedLocationPolicy = BalancedLocationPolicy().also {
        (0L..180L step 60).forEach { second -> observe(it, second) }
        assertTrue(it.isStationary)
        assertTrue(it.beginMotionProbe(240_000))
    }
    @Test fun timeoutRetainsUncertaintyAndLowFrequencyBoundedChecks() {
        val policy = probe()
        policy.endConfirmation(360_000)
        assertTrue(policy.hasUnconfirmedMotion)
        assertEquals(480_000L, policy.recoveryDelayMs(360_000))
        assertFalse(policy.beginRecovery(839_999))
        assertTrue(policy.beginRecovery(840_000))
        assertEquals(120_000L, policy.confirmationDelayMs(840_000))
        policy.endConfirmation(960_000)
        assertEquals(480_000L, policy.recoveryDelayMs(960_000))
        assertFalse(policy.beginMotionProbe(1_000_000))
        assertTrue(policy.beginRecovery(1_440_000))
        policy.endConfirmation(1_560_000)
        assertTrue(policy.hasUnconfirmedMotion)
        assertEquals(480_000L, policy.recoveryDelayMs(1_560_000))
    }
    @Test fun oneNewPointCannotBorrowOldStayEvidenceToCancelRecovery() {
        val policy = probe()
        policy.endConfirmation(360_000)
        assertTrue(observe(policy, 370))
        assertEquals(600_000L, policy.recoveryDelayMs(370_000))
        assertTrue(policy.hasUnconfirmedMotion)
    }
    @Test fun freshStayEvidencePostponesWatchWithoutCreatingAStationaryReport() {
        val policy = probe()
        for (second in listOf(250L, 290L, 330L)) assertTrue(observe(policy, second))
        policy.endConfirmation(360_000)
        assertTrue(observe(policy, 370))
        assertFalse(policy.hasUnconfirmedMotion)
        assertTrue(policy.isStationary)
        assertEquals(600_000L, policy.recoveryDelayMs(370_000))
        assertNull(policy.confirmationDelayMs(370_000))
        val old = point(180)
        assertFalse(LocationUploadPolicy.shouldUpload(start + 370_000, point(370),
            UploadedLocation(old.latitude, old.longitude, old.accuracyMeters, old.locationTimeMs, old.locationTimeMs, old.provider)))
    }
    @Test fun confirmedDisplacementCancelsRecoveryAndRestoresMinuteSampling() {
        val policy = probe()
        policy.endConfirmation(360_000)
        assertFalse(observe(policy, 370, 23.132))
        assertTrue(observe(policy, 400, 23.1321))
        assertFalse(policy.hasUnconfirmedMotion)
        assertFalse(policy.isStationary)
        assertNull(policy.recoveryDelayMs(400_000))
        assertEquals(60_000L, policy.intervalMs)
    }
    @Test fun cacheAndPoorFixesCannotCancelOrPostponeRecovery() {
        val policy = probe()
        policy.endConfirmation(360_000)
        for (second in listOf(370L, 380L, 390L, 400L)) {
            val p = point(second)
            assertFalse(policy.observe(p.locationTimeMs, p.copy(knownCached = true), null, second * 1000))
            assertFalse(policy.observe(p.locationTimeMs, p.copy(accuracyMeters = 200f), null, second * 1000))
        }
        assertTrue(policy.hasUnconfirmedMotion)
        assertEquals(440_000L, policy.recoveryDelayMs(400_000))
        assertTrue(policy.beginRecovery(840_000))
    }
    @Test fun elapsedCatchupStartsOnlyOneFreshWindow() {
        val policy = probe()
        policy.endConfirmation(360_000)
        assertTrue(policy.beginRecovery(2_000_000))
        assertFalse(policy.beginRecovery(2_000_000))
        assertEquals(120_000L, policy.confirmationDelayMs(2_000_000))
        policy.endConfirmation(2_120_000)
        assertEquals(480_000L, policy.recoveryDelayMs(2_120_000))
    }
    @Test fun coordinateConfirmationTimeoutAlsoHasBoundedRecovery() {
        val policy = BalancedLocationPolicy()
        (0L..180L step 60).forEach { observe(policy, it) }
        assertFalse(observe(policy, 240, 23.132))
        policy.endConfirmation(360_000)
        assertTrue(policy.hasUnconfirmedMotion)
        assertTrue(policy.beginRecovery(840_000))
    }
    @Test fun preMotionFixDeliveredLateDoesNotCountAsNewStayEvidence() {
        val policy = probe()
        assertTrue(policy.observe(start + 250_000, point(190), null, 250_000))
        for (second in listOf(250L, 280L, 310L)) assertTrue(observe(policy, second))
        assertTrue("pre-motion observation must not shorten the new stay window", policy.hasUnconfirmedMotion)
        assertEquals(50_000L, policy.confirmationDelayMs(310_000))
    }
    @Test fun fixesWithoutObservationClockCannotProvePostMotionStay() {
        val policy = probe()
        for (second in listOf(250L, 290L, 330L, 370L)) {
            assertTrue(policy.observe(start + second * 1000, point(second).copy(elapsedRealtimeNanos = 0), null, second * 1000))
        }
        assertTrue(policy.hasUnconfirmedMotion)
        assertNotNull(policy.recoveryDelayMs(370_000))
    }
}
