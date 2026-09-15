package com.yuyan.imemodule.data.collect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationUploadPolicyTest {
    private val now = 1_800_000_000_000L

    @Test
    fun `first fresh accurate location is uploaded`() {
        assertTrue(LocationUploadPolicy.shouldUpload(now, candidate(), null))
    }

    @Test
    fun `location older than one minute is rejected`() {
        assertFalse(
            LocationUploadPolicy.shouldUpload(
                now,
                candidate(locationTimeMs = now - 60_001),
                null,
            ),
        )
    }

    @Test
    fun `location exactly one minute old is accepted`() {
        assertTrue(
            LocationUploadPolicy.shouldUpload(
                now,
                candidate(locationTimeMs = now - 60_000),
                null,
            ),
        )
    }

    @Test
    fun `future location and invalid accuracy are rejected`() {
        assertFalse(LocationUploadPolicy.shouldUpload(now, candidate(locationTimeMs = now + 1), null))
        assertFalse(LocationUploadPolicy.shouldUpload(now, candidate(accuracyMeters = -1f), null))
        assertFalse(LocationUploadPolicy.shouldUpload(now, candidate(accuracyMeters = 200.1f), null))
    }

    @Test
    fun `successful upload is rate limited for one minute`() {
        val last = uploaded(uploadedAtMs = now - 59_999)
        val moved = candidate(latitude = 23.136)

        assertFalse(LocationUploadPolicy.shouldUpload(now, moved, last))
        assertTrue(LocationUploadPolicy.shouldUpload(now, moved, last.copy(uploadedAtMs = now - 60_000)))
    }

    @Test
    fun `movement must exceed base distance`() {
        val last = uploaded(accuracyMeters = 5f)

        assertFalse(LocationUploadPolicy.shouldUpload(now, candidate(latitude = 23.1352, accuracyMeters = 5f), last))
        assertTrue(LocationUploadPolicy.shouldUpload(now, candidate(latitude = 23.1360, accuracyMeters = 5f), last))
    }

    @Test
    fun `movement must exceed combined accuracy when it is larger`() {
        val last = uploaded(accuracyMeters = 40f)

        assertFalse(LocationUploadPolicy.shouldUpload(now, candidate(latitude = 23.1356, accuracyMeters = 40f), last))
        assertTrue(LocationUploadPolicy.shouldUpload(now, candidate(latitude = 23.1360, accuracyMeters = 40f), last))
    }

    private fun candidate(
        latitude: Double = 23.1350,
        longitude: Double = 113.2360,
        accuracyMeters: Float = 10f,
        locationTimeMs: Long = now,
    ) = LocationCandidate(latitude, longitude, accuracyMeters, locationTimeMs)

    private fun uploaded(
        latitude: Double = 23.1350,
        longitude: Double = 113.2360,
        accuracyMeters: Float = 10f,
        locationTimeMs: Long = now - 120_000,
        uploadedAtMs: Long = now - 120_000,
    ) = UploadedLocation(latitude, longitude, accuracyMeters, locationTimeMs, uploadedAtMs)
}
