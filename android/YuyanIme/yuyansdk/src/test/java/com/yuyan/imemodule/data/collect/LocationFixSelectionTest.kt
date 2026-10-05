package com.yuyan.imemodule.data.collect

import android.location.Location
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class LocationFixSelectionTest {
    private val now = 1_800_000_000_000L
    private fun fix(provider: String, age: Long, error: Float) = Location(provider).apply {
        latitude = 23.0; longitude = 113.0; time = now - age; accuracy = error
    }
    @Test fun `recent accurate gps wins over slightly newer poor network fix`() {
        val gps = fix("gps", 10_000, 5f)
        assertSame(gps, selectInitialLocation(now, listOf(gps, fix("network", 0, 40f))))
    }
    @Test fun `old gps cannot overshadow substantially newer network fix`() {
        val network = fix("network", 0, 25f)
        assertSame(network, selectInitialLocation(now, listOf(fix("gps", 50_000, 5f), network)))
    }
    @Test fun `stale future missing accuracy and hundred metre fixes are not selected`() {
        val missing = fix("network", 0, 5f).apply { removeAccuracy() }
        assertNull(selectInitialLocation(now, listOf(fix("gps", 60_001, 5f),
            fix("gps", -1, 5f), fix("network", 0, 100f), missing)))
    }
    @Test fun `same accuracy favors newer fix and preserves original timestamp`() {
        val newer = fix("network", 1000, 10f)
        assertSame(newer, selectInitialLocation(now, listOf(fix("gps", 2000, 10f), newer)))
        assertEquals(now - 1000, newer.time)
    }
}
