package com.yuyan.imemodule.data.collect

import org.junit.Assert.*
import org.junit.Test

class BalancedLocationPolicyTest {
    private val start = 1_800_000_000_000L
    private fun point(time: Long, lat: Double = 23.13) = LocationCandidate(lat, 113.3, 15f, time)

    @Test fun `single unconfirmed jump briefly checks movement then restores stationary sampling`() {
        val policy = BalancedLocationPolicy()
        (0..4).forEach { policy.observe(start + it * 30_000, point(start + it * 30_000), null) }
        policy.observe(start + 150_000, point(start + 150_000, 23.15), null)
        assertEquals(30_000L, policy.intervalMs)
        policy.observe(start + 180_000, point(start + 180_000), null)
        assertEquals(300_000L, policy.intervalMs)
    }

    @Test fun `unconfirmed excursions do not keep stationary high frequency forever`() {
        val policy = BalancedLocationPolicy()
        (0..4).forEach { policy.observe(start + it * 30_000, point(start + it * 30_000), null) }
        policy.observe(start + 150_000, point(start + 150_000, 23.131), null)
        assertEquals(30_000L, policy.intervalMs)
        policy.observe(start + 180_000, point(start + 180_000, 23.129), null)
        policy.observe(start + 210_000, point(start + 210_000, 23.131), null)
        policy.observe(start + 240_000, point(start + 240_000, 23.129), null)
        assertEquals(300_000L, policy.intervalMs)
        policy.observe(start + 540_000, point(start + 540_000), null)
        assertEquals(300_000L, policy.intervalMs)
    }

    @Test fun `observation precedes stationary sampling and movement restores thirty seconds`() {
        val policy = BalancedLocationPolicy()
        assertEquals(30_000L, policy.intervalMs)
        (0..3).forEach { policy.observe(start + it * 30_000, point(start + it * 30_000), 0f) }
        assertEquals(30_000L, policy.intervalMs)
        policy.observe(start + 120_000, point(start + 120_000), 0f)
        assertEquals(300_000L, policy.intervalMs)
        policy.observe(start + 420_000, point(start + 420_000, 23.14), null)
        assertEquals(30_000L, policy.intervalMs)
    }

    @Test fun `stale points and sparse observations cannot establish a stay`() {
        val policy = BalancedLocationPolicy()
        policy.observe(start, point(start), null)
        policy.observe(start + 120_000, point(start), null)
        policy.observe(start + 300_000, point(start + 300_000), null)
        assertEquals(30_000L, policy.intervalMs)
    }

    @Test fun `speed restores movement even inside uncertainty radius`() {
        val policy = BalancedLocationPolicy()
        (0..4).forEach { policy.observe(start + it * 30_000, point(start + it * 30_000), 0f) }
        policy.observe(start + 150_000, point(start + 150_000), 2f)
        assertEquals(30_000L, policy.intervalMs)
    }

    @Test fun `balanced upload requires movement even after its sampling interval`() {
        val last = UploadedLocation(23.13, 113.3, 15f, start, start)
        assertFalse(LocationUploadPolicy.shouldUpload(start + 300_000, point(start + 300_000), last, 300_000))
        assertFalse(LocationUploadPolicy.shouldUpload(start + 300_000, point(start), last, 300_000))
        assertFalse(LocationUploadPolicy.shouldUpload(start + 29_999, point(start + 29_999, 23.14), last, 30_000))
        assertFalse(LocationUploadPolicy.shouldUpload(start + 30_000, point(start + 30_000), last, 30_000))
        assertTrue(LocationUploadPolicy.shouldUpload(start + 30_000, point(start + 30_000, 23.14), last, 30_000))
        assertTrue(LocationUploadPolicy.shouldUpload(start + 300_000, point(start + 300_000, 23.14), last, 300_000))
    }
    @Test fun `long observation gap resets stay confidence`() {
        val policy = BalancedLocationPolicy()
        (0..4).forEach { policy.observe(start + it * 30_000, point(start + it * 30_000), null) }
        policy.observe(start + 1_200_000, point(start + 1_200_000), null)
        assertEquals(30_000L, policy.intervalMs)
    }

    @Test fun `snapshot processing delay does not lose a changed location at sampling interval`() {
        val last = UploadedLocation(23.13, 113.3, 15f, start, start + 150)
        assertTrue(LocationUploadPolicy.shouldUpload(start + 300_020, point(start + 300_000, 23.14), last, 300_000))
    }

}
