package com.yuyan.imemodule.data.collect

import android.location.Location
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class LocationQualityPolicyTest {
    private val start = 1_800_000_000_000L
    private fun point(time: Long, latitude: Double = 23.13, provider: String = "gps") =
        LocationCandidate(latitude, 113.3, 15f, time, provider, (time - start + 1_000_000) * 1_000_000)

    @Test fun `missing and invalid speeds are unavailable`() {
        for (speed in listOf(null, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val location = Location("network").apply { accuracy = 10f; if (speed != null) this.speed = speed }
            val quality = LocationSpeedQuality.from(location)
            assertNull(quality.speedMps)
            assertNull(quality.rawSpeedMps)
            assertEquals("unavailable", quality.quality)
        }
    }

    @Test fun `poor or missing speed accuracy is unknown instead of zero or walking speed`() {
        val location = Location("network").apply { accuracy = 100f; speed = 50.2f / 3.6f }
        assertNull(LocationSpeedQuality.from(location).speedMps)
        assertEquals(location.speed, LocationSpeedQuality.from(location).rawSpeedMps)
        location.accuracy = 10f
        assertEquals("missing_speed_accuracy", LocationSpeedQuality.from(location).reason)
        location.speedAccuracyMetersPerSecond = 10f
        assertEquals("poor_speed_accuracy", LocationSpeedQuality.from(location).reason)
    }

    @Test fun `accurate highway and train speeds are retained without a speed ceiling`() {
        for (speed in listOf(0f, 1.4f, 30f, 100f)) {
            val location = Location("gps").apply {
                accuracy = 10f; this.speed = speed; speedAccuracyMetersPerSecond = 0.5f
            }
            assertEquals(speed, LocationSpeedQuality.from(location).speedMps)
            assertEquals("trusted", LocationSpeedQuality.from(location).quality)
        }
    }

    @Test fun `single jump followed by return is ignored and clears confirmation`() {
        val filter = LocationJumpFilter()
        assertTrue(filter.accept(start, point(start), null))
        assertFalse(filter.accept(start + 30_000, point(start + 30_000, 23.15), null))
        assertTrue(filter.accept(start + 60_000, point(start + 60_000), null))
        assertFalse(filter.accept(start + 90_000, point(start + 90_000, 23.15), null))
    }

    @Test fun `independent fix near suspected destination confirms relocation`() {
        val filter = LocationJumpFilter()
        filter.accept(start, point(start), null)
        assertFalse(filter.accept(start + 30_000, point(start + 30_000, 23.15), null))
        assertTrue(filter.accept(start + 60_000, point(start + 60_000, 23.1501), null))
    }

    @Test fun `same cached fix cannot confirm jump even if delivery or wall time changes`() {
        val filter = LocationJumpFilter()
        filter.accept(start, point(start), null)
        val jump = point(start + 30_000, 23.15)
        assertFalse(filter.accept(start + 30_000, jump, null))
        assertFalse(filter.accept(start + 40_000, jump, null))
        assertFalse(filter.accept(start + 40_000, jump.copy(locationTimeMs = start + 40_000), null))
        assertTrue(filter.accept(start + 60_000, point(start + 60_000, 23.15), null))
    }

    @Test fun `accepted fix can retry failed persistence without confirming a pending jump`() {
        val filter = LocationJumpFilter()
        val candidate = point(start)
        assertTrue(filter.accept(start, candidate, null))
        // First enqueue failed: no persisted baseline was advanced.
        assertTrue(filter.accept(start + 1_000, candidate, null))
        assertTrue(LocationUploadPolicy.shouldUpload(start + 1_000, candidate, null))
        val stored = UploadedLocation(candidate.latitude, candidate.longitude, candidate.accuracyMeters,
            candidate.locationTimeMs, start + 1_000, candidate.provider)
        assertFalse(LocationUploadPolicy.shouldUpload(start + 2_000, candidate, stored))
        val jump = point(start + 30_000, 23.15)
        assertFalse(filter.accept(start + 30_000, jump, null))
        assertFalse(filter.accept(start + 31_000, jump, null))
    }

    @Test fun `source switch with unexplained displacement requires confirmation`() {
        val filter = LocationJumpFilter()
        filter.accept(start, point(start), null)
        assertFalse(filter.accept(start + 30_000, point(start + 30_000, 23.132, "network"), null))
        assertTrue(filter.accept(start + 60_000, point(start + 60_000, 23.1321, "network"), null))
    }

    @Test fun `source switch alone does not reject nearby location`() {
        val filter = LocationJumpFilter()
        filter.accept(start, point(start), null)
        assertTrue(filter.accept(start + 30_000, point(start + 30_000, 23.1301, "network"), null))
    }

    @Test fun `reliable high speed supports large displacement even across providers`() {
        val filter = LocationJumpFilter()
        filter.accept(start, point(start), 100f)
        assertTrue(filter.accept(start + 30_000, point(start + 30_000, 23.157, "network"), 100f))
    }

    @Test fun `large displacement inconsistent with trusted low speed is confirmed first`() {
        val filter = LocationJumpFilter()
        filter.accept(start, point(start), 1f)
        assertFalse(filter.accept(start + 30_000, point(start + 30_000, 23.15), 1f))
    }

    @Test fun `long observation gap is not interpreted as instantaneous speed`() {
        val filter = LocationJumpFilter()
        filter.accept(start, point(start), null)
        assertFalse(filter.accept(start + 3_600_000, point(start + 3_600_000, 24.0, "network"), null))
        assertTrue(filter.accept(start + 3_630_000, point(start + 3_630_000, 24.0001, "network"), null))
    }

    @Test fun `stale and out of order fixes do not confirm relocation`() {
        val filter = LocationJumpFilter()
        filter.accept(start, point(start), null)
        assertFalse(filter.accept(start + 30_000, point(start + 30_000, 23.15), null))
        assertFalse(filter.accept(start + 120_001, point(start + 60_000, 23.15), null))
        assertFalse(filter.accept(start + 30_000, point(start + 20_000, 23.15), null))
        assertTrue(filter.accept(start + 60_000, point(start + 60_000, 23.15), null))
    }
}
