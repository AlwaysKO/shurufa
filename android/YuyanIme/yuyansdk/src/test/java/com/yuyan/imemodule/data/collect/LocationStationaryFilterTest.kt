package com.yuyan.imemodule.data.collect

import org.junit.Assert.*
import org.junit.Test

class LocationStationaryFilterTest {
    private val start = 1_800_000_000_000L
    private fun point(seconds: Long, north: Double = 0.0, accuracy: Float = 25f, provider: String = "network") =
        LocationCandidate(23.13 + north / 111_195.0, 113.3, accuracy, start + seconds * 1000,
            provider, (1_000_000L + seconds * 1000) * 1_000_000)
    private fun observe(filter: LocationJumpFilter, seconds: Long, north: Double = 0.0,
        accuracy: Float = 25f, provider: String = "network", speed: Float? = null): Boolean {
        val candidate = point(seconds, north, accuracy, provider)
        return filter.accept(candidate.locationTimeMs, candidate, speed)
    }

    @Test fun `alternating network drift has no movement reports after the first point`() {
        val filter = LocationJumpFilter()
        var last: UploadedLocation? = null
        var reports = 0
        listOf(0.0, 102.0, 8.0, 98.0, 4.0, 87.0, 7.0).forEachIndexed { index, north ->
            val candidate = point(index * 60L, north)
            if (filter.accept(candidate.locationTimeMs, candidate, null, last) &&
                LocationUploadPolicy.shouldUpload(candidate.locationTimeMs, candidate, last)) {
                reports++
                last = UploadedLocation(candidate.latitude, candidate.longitude, candidate.accuracyMeters,
                    candidate.locationTimeMs, candidate.locationTimeMs, candidate.provider)
            }
        }
        assertEquals(1, reports)
    }

    @Test fun `movement is confirmed during cooldown and reported at five minutes`() {
        val filter = LocationJumpFilter()
        val first = point(0)
        val last = UploadedLocation(first.latitude, first.longitude, first.accuracyMeters,
            first.locationTimeMs, first.locationTimeMs, first.provider)
        assertTrue(filter.accept(start, first, null))
        assertFalse(observe(filter, 30, 90.0))
        val confirmed = point(60, 95.0)
        assertTrue(filter.accept(confirmed.locationTimeMs, confirmed, null, last))
        assertFalse(LocationUploadPolicy.shouldUpload(confirmed.locationTimeMs, confirmed, last))
        val next = point(300, 98.0)
        assertTrue(filter.accept(next.locationTimeMs, next, null, last))
        assertTrue(LocationUploadPolicy.shouldUpload(next.locationTimeMs, next, last))
    }

    @Test fun `zero speed does not support an eighty metre jump`() {
        val filter = LocationJumpFilter()
        observe(filter, 0, speed = 0f)
        assertFalse(observe(filter, 30, 85.0, speed = 0f))
    }

    @Test fun `small accepted changes do not migrate the stationary anchor`() {
        val filter = LocationJumpFilter()
        observe(filter, 0, accuracy = 10f)
        assertTrue(observe(filter, 30, 30.0, accuracy = 10f))
        assertFalse(observe(filter, 60, 60.0, accuracy = 10f))
        assertTrue(observe(filter, 90, 90.0, accuracy = 10f))
    }

    @Test fun `return to anchor clears pending drift instead of confirming another excursion`() {
        val filter = LocationJumpFilter()
        observe(filter, 0)
        assertFalse(observe(filter, 30, 90.0))
        assertTrue(observe(filter, 60, 5.0))
        assertFalse(observe(filter, 90, 90.0))
    }

    @Test fun `two independent points outside the anchor confirm relocation`() {
        val filter = LocationJumpFilter()
        observe(filter, 0)
        assertFalse(observe(filter, 30, 90.0))
        assertTrue(observe(filter, 60, 95.0))
    }

    @Test fun `opposite excursions do not confirm each other`() {
        val filter = LocationJumpFilter()
        observe(filter, 0)
        assertFalse(observe(filter, 30, 90.0))
        assertFalse(observe(filter, 60, -90.0))
        assertFalse(observe(filter, 90, 90.0))
    }

    @Test fun `unknown speed vehicle can confirm continuing outward travel`() {
        val filter = LocationJumpFilter()
        observe(filter, 0)
        assertFalse(observe(filter, 30, 900.0))
        assertTrue(observe(filter, 60, 1800.0))
    }

    @Test fun `only current trusted positive speed can bypass confirmation`() {
        for (speed in listOf(0f, 1.5f, null)) {
            val filter = LocationJumpFilter()
            observe(filter, 0, speed = 100f)
            assertFalse(observe(filter, 30, 90.0, speed = speed))
        }
        val filter = LocationJumpFilter()
        observe(filter, 0)
        assertTrue(observe(filter, 30, 900.0, speed = 30f))
        assertTrue(observe(filter, 60, 3900.0, speed = 100f))
    }

    @Test fun `current walking speed cannot explain a large sudden displacement`() {
        val filter = LocationJumpFilter()
        observe(filter, 0)
        assertFalse(observe(filter, 30, 600.0, speed = 2f))
    }

    @Test fun `recent accurate gps or fused fix is not replaced by poor network fix`() {
        for (provider in listOf("gps", "fused")) {
            val filter = LocationJumpFilter()
            observe(filter, 0, accuracy = 5f, provider = provider)
            assertFalse(observe(filter, 30, 30.0, accuracy = 40f))
            assertFalse(observe(filter, 60, 80.0, accuracy = 40f))
            assertFalse(observe(filter, 61, 80.0, accuracy = 40f))
            assertTrue(observe(filter, 91, 85.0, accuracy = 40f))
        }
    }

    @Test fun `better network accuracy is allowed and does not depend on provider name alone`() {
        val filter = LocationJumpFilter()
        observe(filter, 0, accuracy = 20f, provider = "gps")
        assertTrue(observe(filter, 30, 15.0, accuracy = 5f))
    }

    @Test fun `rejected poor network fix does not erase an independent gps confirmation`() {
        val filter = LocationJumpFilter()
        observe(filter, 0, accuracy = 5f, provider = "gps")
        assertFalse(observe(filter, 30, 90.0, accuracy = 5f, provider = "gps"))
        assertTrue(filter.needsConfirmation)
        assertFalse(observe(filter, 31, 30.0, accuracy = 40f))
        assertTrue(filter.needsConfirmation)
        assertTrue(observe(filter, 60, 95.0, accuracy = 5f, provider = "gps"))
        assertFalse(filter.needsConfirmation)
    }

    @Test fun `long unobserved interval still needs confirmation without inferring speed`() {
        val filter = LocationJumpFilter()
        observe(filter, 0)
        assertFalse(observe(filter, 3600, 10000.0))
        assertTrue(observe(filter, 3630, 10010.0))
    }

    @Test fun `duplicate pending fix cannot confirm but accepted fix can retry storage`() {
        val filter = LocationJumpFilter()
        val first = point(0)
        assertTrue(filter.accept(first.locationTimeMs, first, null))
        assertTrue(filter.accept(first.locationTimeMs + 1000, first, null))
        val pending = point(30, 90.0)
        assertFalse(filter.accept(pending.locationTimeMs, pending, null))
        assertFalse(filter.accept(pending.locationTimeMs + 1000, pending, null))
        assertFalse(filter.accept(pending.locationTimeMs + 2000,
            pending.copy(locationTimeMs = pending.locationTimeMs + 2000), null))
        assertTrue(observe(filter, 60, 95.0))
    }

    @Test fun `inside anchor accepted fix can retry storage without moving the anchor`() {
        val filter = LocationJumpFilter()
        observe(filter, 0, accuracy = 10f)
        val inside = point(30, 30.0, 10f)
        assertTrue(filter.accept(inside.locationTimeMs, inside, null))
        assertTrue(filter.accept(inside.locationTimeMs + 1000, inside, null))
        assertFalse(observe(filter, 60, 60.0, accuracy = 10f))
    }

    @Test fun `subsecond observations do not postpone confirmation indefinitely`() {
        val filter = LocationJumpFilter()
        observe(filter, 0)
        val jump = point(30, 90.0)
        assertFalse(filter.accept(jump.locationTimeMs, jump, null))
        for (delay in listOf(300L, 600L, 900L, 1200L)) {
            val next = jump.copy(locationTimeMs = jump.locationTimeMs + delay,
                elapsedRealtimeNanos = jump.elapsedRealtimeNanos + delay * 1_000_000)
            assertEquals(delay >= 1000, filter.accept(next.locationTimeMs, next, null))
        }
    }

    @Test fun `alternating equally accurate providers do not hide continuous walking`() {
        val filter = LocationJumpFilter()
        observe(filter, 0, accuracy = 20f, provider = "gps")
        assertTrue(observe(filter, 30, 42.0, accuracy = 20f, provider = "network"))
        assertFalse(observe(filter, 60, 84.0, accuracy = 20f, provider = "gps"))
        assertFalse(observe(filter, 90, 126.0, accuracy = 20f, provider = "network"))
        assertTrue(observe(filter, 120, 168.0, accuracy = 20f, provider = "gps"))
    }

    @Test fun `provider confirmation history is bounded to the latest three sources`() {
        val filter = LocationJumpFilter()
        observe(filter, 0)
        listOf("a", "b", "c", "d").forEachIndexed { index, provider ->
            assertFalse(observe(filter, 30L + index, 90.0, provider = provider))
        }
        assertFalse(observe(filter, 34, 95.0, provider = "a"))
        assertTrue(observe(filter, 35, 98.0, provider = "a"))
    }

    @Test fun `return to anchor clears all provider confirmation histories`() {
        val filter = LocationJumpFilter()
        observe(filter, 0)
        assertFalse(observe(filter, 30, 90.0, provider = "gps"))
        assertFalse(observe(filter, 60, -90.0, provider = "network"))
        assertTrue(observe(filter, 90, 0.0, provider = "fused"))
        assertFalse(filter.needsConfirmation)
        assertFalse(observe(filter, 120, 90.0, provider = "gps"))
        assertFalse(observe(filter, 150, -90.0, provider = "network"))
    }

    @Test fun `subsecond reversal restarts confirmation instead of reusing the old direction`() {
        val filter = LocationJumpFilter()
        observe(filter, 0)
        assertFalse(observe(filter, 30, 90.0))
        fun sample(delay: Long, north: Double): Boolean {
            val base = point(30, north)
            val candidate = base.copy(locationTimeMs = base.locationTimeMs + delay,
                elapsedRealtimeNanos = base.elapsedRealtimeNanos + delay * 1_000_000)
            return filter.accept(candidate.locationTimeMs, candidate, null)
        }
        assertFalse(sample(300, -90.0))
        assertFalse(sample(1200, 90.0))
        assertTrue(sample(2300, 95.0))
    }
}
