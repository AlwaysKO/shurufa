package com.yuyan.imemodule.data.collect

import org.junit.Assert.*
import org.junit.Test

class BalancedLocationPolicyTest {
    private val start = 1_800_000_000_000L
    private fun point(time: Long, lat: Double = 23.13) = LocationCandidate(lat, 113.3, 15f, time)

    @Test fun `normal movement requests and persists minute captures while confirmation stays bounded`() {
        val policy = BalancedLocationPolicy()
        assertEquals(60_000L, policy.intervalMs)
        val last = UploadedLocation(23.13, 113.3, 15f, start, start)
        assertFalse(LocationUploadPolicy.shouldUpload(start + 59_999, point(start + 59_999, 23.14), last))
        assertTrue(LocationUploadPolicy.shouldUpload(start + 60_000, point(start + 60_000, 23.14), last))
    }

    @Test fun `expired confirmation can retry after cooldown without a new accepted anchor`() {
        val policy = BalancedLocationPolicy().apply { updateNetwork(true) }
        (0..4).forEach { policy.observe(start + it * 30_000, point(start + it * 30_000), null) }
        policy.observe(start + 150_000, point(start + 150_000, 23.131), null)
        policy.endConfirmation()
        policy.observe(start + 180_000, point(start + 180_000, 23.129), null)
        assertEquals("cooldown must not restart on every rejected point", 300_000L, policy.intervalMs)
        policy.observe(start + 330_000, point(start + 330_000, 23.131), null)
        assertEquals(300_000L, policy.intervalMs)
        assertTrue(policy.beginMotionProbe(start + 750_000))
        assertEquals("new evidence after cooldown deserves a bounded retry", 30_000L, policy.intervalMs)
        assertEquals(120_000L, policy.confirmationDelayMs(start + 750_000))
    }

    @Test fun `moving requests one minute on both networks and cellular stay uses one minute`() {
        for (wifi in listOf(false, true)) {
            val policy = BalancedLocationPolicy().apply { updateNetwork(wifi) }
            assertEquals(60_000L, policy.intervalMs)
            (0..4).forEach { policy.observe(start + it * 30_000, point(start + it * 30_000), 0f) }
            assertEquals(if (wifi) 300_000L else 60_000L, policy.intervalMs)
        }
    }

    @Test fun `precise continuous walking does not enter wifi stationary cadence`() {
        val policy = BalancedLocationPolicy().apply { updateNetwork(true) }
        for (minute in 0..6) {
            val time = start + minute * 60_000
            policy.observe(time, LocationCandidate(23.13 + minute * 20.0 / 111_195,
                113.3, 5f, time, "gps"), null)
            assertEquals(60_000L, policy.intervalMs)
        }
    }

    @Test fun `non wifi stationary observations retain one minute sampling`() {
        val policy = BalancedLocationPolicy()
        (0..4).forEach { policy.observe(start + it * 30_000, point(start + it * 30_000), null) }
        assertEquals(60_000L, policy.intervalMs)
    }

    @Test fun `network transition preserves geographical stay and confirmation expiry uses current network`() {
        val policy = BalancedLocationPolicy().apply { updateNetwork(true) }
        (0..4).forEach { policy.observe(start + it * 30_000, point(start + it * 30_000), null) }
        assertEquals(300_000L, policy.intervalMs)
        policy.updateNetwork(false)
        assertEquals(60_000L, policy.intervalMs)
        policy.updateNetwork(true)
        assertEquals(300_000L, policy.intervalMs)
        policy.observe(start + 150_000, point(start + 150_000, 23.15), null)
        assertEquals(30_000L, policy.intervalMs)
        policy.updateNetwork(false)
        policy.endConfirmation()
        assertEquals(60_000L, policy.intervalMs)
        assertNull(policy.confirmationDelayMs(start + 210_000))
        policy.updateNetwork(true)
        assertEquals(300_000L, policy.intervalMs)
    }

    @Test fun `single unconfirmed jump briefly checks movement then restores stationary sampling`() {
        val policy = BalancedLocationPolicy().apply { updateNetwork(true) }
        (0..4).forEach { policy.observe(start + it * 30_000, point(start + it * 30_000), null) }
        policy.observe(start + 150_000, point(start + 150_000, 23.15), null)
        assertEquals(30_000L, policy.intervalMs)
        policy.observe(start + 180_000, point(start + 180_000), null)
        assertEquals(300_000L, policy.intervalMs)
    }

    @Test fun `unconfirmed excursions do not keep stationary high frequency forever`() {
        val policy = BalancedLocationPolicy().apply { updateNetwork(true) }
        (0..4).forEach { policy.observe(start + it * 30_000, point(start + it * 30_000), null) }
        policy.observe(start + 150_000, point(start + 150_000, 23.131), null)
        assertEquals(30_000L, policy.intervalMs)
        policy.observe(start + 180_000, point(start + 180_000, 23.129), null)
        policy.observe(start + 210_000, point(start + 210_000, 23.131), null)
        policy.observe(start + 240_000, point(start + 240_000, 23.129), null)
        assertEquals(30_000L, policy.intervalMs)
        policy.observe(start + 270_000, point(start + 270_000, 23.131), null)
        assertEquals(300_000L, policy.intervalMs)
        policy.observe(start + 540_000, point(start + 540_000), null)
        assertEquals(300_000L, policy.intervalMs)
    }

    @Test fun `one minute samples establish a stay and confirmed motion restores one minute`() {
        val policy = BalancedLocationPolicy().apply { updateNetwork(true) }
        assertEquals(60_000L, policy.intervalMs)
        (0..2).forEach { policy.observe(start + it * 60_000, point(start + it * 60_000), 0f) }
        assertEquals(60_000L, policy.intervalMs)
        policy.observe(start + 180_000, point(start + 180_000), 0f)
        assertEquals(300_000L, policy.intervalMs)
        policy.observe(start + 480_000, point(start + 480_000, 23.14), null)
        assertEquals(30_000L, policy.intervalMs)
        assertEquals(120_000L, policy.confirmationDelayMs(start + 480_000))
        policy.observe(start + 510_000, point(start + 510_000, 23.1401), null)
        assertEquals(60_000L, policy.intervalMs)
        assertNull(policy.confirmationDelayMs(start + 510_000))
    }

    @Test fun `stale points and sparse observations cannot establish a stay`() {
        val policy = BalancedLocationPolicy().apply { updateNetwork(true) }
        policy.observe(start, point(start), null)
        policy.observe(start + 120_000, point(start), null)
        policy.observe(start + 300_000, point(start + 300_000), null)
        assertEquals(60_000L, policy.intervalMs)
    }

    @Test fun `speed restores movement even inside uncertainty radius`() {
        val policy = BalancedLocationPolicy().apply { updateNetwork(true) }
        (0..4).forEach { policy.observe(start + it * 30_000, point(start + it * 30_000), 0f) }
        policy.observe(start + 150_000, point(start + 150_000), 2f)
        assertEquals(60_000L, policy.intervalMs)
    }

    @Test fun `balanced minute sampling requires one minute and movement to report`() {
        val last = UploadedLocation(23.13, 113.3, 15f, start, start)
        assertFalse(LocationUploadPolicy.shouldUpload(start + 300_000, point(start + 300_000), last))
        assertFalse(LocationUploadPolicy.shouldUpload(start + 300_000, point(start), last))
        assertFalse(LocationUploadPolicy.shouldUpload(start + 29_999, point(start + 29_999, 23.14), last))
        assertFalse(LocationUploadPolicy.shouldUpload(start + 30_000, point(start + 30_000), last))
        assertFalse(LocationUploadPolicy.shouldUpload(start + 30_000, point(start + 30_000, 23.14), last))
        assertTrue(LocationUploadPolicy.shouldUpload(start + 60_000, point(start + 60_000, 23.14), last))
    }
    @Test fun `long observation gap resets stay confidence`() {
        val policy = BalancedLocationPolicy().apply { updateNetwork(true) }
        (0..4).forEach { policy.observe(start + it * 30_000, point(start + it * 30_000), null) }
        policy.observe(start + 1_200_000, point(start + 1_200_000), null)
        assertEquals(60_000L, policy.intervalMs)
    }

    @Test fun `minute captures tolerate selection delay but dense captures do not`() {
        val last = UploadedLocation(23.13, 113.3, 15f, start, start + 150)
        assertFalse(LocationUploadPolicy.shouldUpload(start + 29_999, point(start + 29_999, 23.14), last))
        assertTrue(LocationUploadPolicy.shouldUpload(start + 60_020, point(start + 60_000, 23.14), last))
        assertTrue(LocationUploadPolicy.shouldUpload(start + 60_150, point(start + 60_000, 23.14), last))
    }

}
