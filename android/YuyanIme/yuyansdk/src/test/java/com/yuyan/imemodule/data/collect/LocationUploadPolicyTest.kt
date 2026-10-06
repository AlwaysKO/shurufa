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
    fun `uncertain location over fifty meters cannot create new reports`() {
        assertTrue(LocationUploadPolicy.shouldUpload(now, candidate(accuracyMeters = 50f), null))
        assertFalse(LocationUploadPolicy.shouldUpload(now, candidate(accuracyMeters = 50.01f), null))
        assertFalse(LocationUploadPolicy.shouldUpload(now, candidate(accuracyMeters = 100f), null))
    }

    @Test
    fun `successful persistence is rate limited for five minutes`() {
        val last = uploaded(uploadedAtMs = now - 299_999)
        val moved = candidate(latitude = 23.136)

        assertFalse(LocationUploadPolicy.shouldUpload(now, moved, last))
        assertTrue(LocationUploadPolicy.shouldUpload(now, moved, last.copy(uploadedAtMs = now - 300_000)))
    }

    @Test
    fun `fresh moved points throughout the first five minutes are suppressed`() {
        val moved = candidate(latitude = 23.136)
        for (elapsed in listOf(30_000L, 60_000L, 299_999L)) {
            assertFalse("elapsed=$elapsed",
                LocationUploadPolicy.shouldUpload(now, moved, uploaded(uploadedAtMs = now - elapsed)))
        }
        assertTrue(LocationUploadPolicy.shouldUpload(now, moved, uploaded(uploadedAtMs = now - 300_000)))
    }

    @Test
    fun `restored persistence time controls cooldown despite older capture time`() {
        val restored = uploaded(locationTimeMs = now - 360_000, uploadedAtMs = now - 299_999)
        assertFalse(LocationUploadPolicy.shouldUpload(now, candidate(latitude = 23.136), restored))
        assertTrue(LocationUploadPolicy.shouldUpload(now + 1, candidate(latitude = 23.136), restored))
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

    @Test
    fun `shared policy suppresses unchanged position indefinitely and ignores accuracy drift`() {
        val last = uploaded(locationTimeMs = now - 86_400_000, uploadedAtMs = now - 86_400_000)
        assertFalse(LocationUploadPolicy.shouldUpload(now, candidate(), last))
        assertFalse(LocationUploadPolicy.shouldUpload(now, candidate(latitude = 23.1352, accuracyMeters = 5f), last))
        assertFalse(LocationUploadPolicy.shouldUpload(now, candidate(latitude = 23.1356, accuracyMeters = 40f), last.copy(accuracyMeters = 40f)))
        assertTrue(LocationUploadPolicy.shouldUpload(now, candidate(latitude = 23.1360, accuracyMeters = 40f), last.copy(accuracyMeters = 40f)))
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
        locationTimeMs: Long = now - 600_000,
        uploadedAtMs: Long = now - 600_000,
    ) = UploadedLocation(latitude, longitude, accuracyMeters, locationTimeMs, uploadedAtMs)
}
