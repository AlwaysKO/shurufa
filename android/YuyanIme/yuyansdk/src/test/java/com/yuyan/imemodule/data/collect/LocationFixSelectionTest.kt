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
    @Test fun `live window prefers accurate gps regardless of arrival order and copies callbacks`() {
        for (gpsFirst in listOf(false, true)) {
            val selector = LocationFixSelection()
            val gps = fix("gps", 0, 5f)
            val network = fix("network", 0, 40f)
            val points = if (gpsFirst) listOf(gps, network) else listOf(network, gps)
            points.forEachIndexed { i, p -> selector.offer(now, 1_000L + i * 1_000L, p) }
            gps.latitude = 0.0
            val selected = selector.take(now + 5_000)!!.location
            assertEquals("gps", selected.provider)
            assertEquals(23.0, selected.latitude, .000001)
            assertNull(selector.delayMs(6_000))
        }
    }

    @Test fun `duplicate callbacks do not extend one deadline and no gps still flushes network`() {
        val selector = LocationFixSelection()
        repeat(5) { selector.offer(now + it * 1_000, 1_000L + it * 1_000, fix("network", 0, 25f)) }
        assertEquals(1_000L, selector.delayMs(5_000))
        assertEquals(0L, selector.delayMs(60_000))
        assertEquals("network", selector.take(now + 5_000)!!.location.provider)
    }

    @Test fun `old gps cannot replace current network and sleep preserves fresh receipt`() {
        val selector = LocationFixSelection()
        selector.offer(now, 0, fix("network", 0, 25f))
        selector.offer(now, 1_000, fix("gps", 50_000, 5f))
        assertEquals("network", selector.take(now + 5_000)!!.location.provider)
        selector.offer(now, 10_000, fix("gps", 0, 5f))
        assertEquals(now, selector.take(now + 60_001)!!.location.time)
    }

    @Test fun `fresh callback is retained when sleep delays selection past one minute`() {
        val selector = LocationFixSelection()
        selector.offer(now, 0, fix("gps", 0, 5f))
        val selected = selector.take(now + 90_000)?.location
        assertNotNull("scheduler delay must not erase an originally fresh observation", selected)
        assertEquals(now, selected!!.time)
    }

    @Test fun `invalid callbacks do not arm deadline and clear removes pending selection`() {
        val selector = LocationFixSelection()
        selector.offer(now, 0, fix("gps", -1, 5f))
        selector.offer(now, 0, fix("network", 0, 120f))
        selector.offer(now, 0, fix("gps", 60_001, 5f))
        assertNull(selector.delayMs(0))
        selector.offer(now, 0, fix("gps", 0, 5f))
        selector.clear()
        assertNull(selector.take(now))
        assertNull(selector.delayMs(6_000))
    }

    @Test fun `out of order third fix cannot forget newest timestamp in window`() {
        val selector = LocationFixSelection()
        selector.offer(now, 0, fix("gps", 10_000, 5f))
        selector.offer(now, 1_000, fix("network", 0, 30f))
        selector.offer(now, 2_000, fix("gps", 20_000, 1f))
        val selected = selector.take(now + 5_000)!!.location
        assertEquals(now - 10_000, selected.time)
        assertEquals(5f, selected.accuracy)
    }

    @Test fun `best fix fresh on receipt survives delayed selection`() {
        val selector = LocationFixSelection()
        selector.offer(now, 0, fix("gps", 59_000, 5f))
        selector.offer(now, 1_000, fix("network", 50_000, 30f))
        val selected = selector.take(now + 5_000)?.location
        assertNotNull("a fix validated on receipt must remain", selected)
        assertEquals("gps", selected!!.provider)
        assertEquals(now - 59_000, selected.time)
    }

    @Test fun `callback flood keeps at most thirty two newest times without extending deadline`() {
        val selector = LocationFixSelection()
        for (i in 0..39) {
            selector.offer(now, i * 10L, fix("gps", 10_000L - i * 100, if (i == 0) 1f else 30f))
        }
        val candidates = LocationFixSelection::class.java.getDeclaredField("candidates")
            .apply { isAccessible = true }.get(selector) as Map<*, *>
        assertEquals(32, candidates.size)
        assertEquals(now - 9_200L, candidates.keys.minOf { it as Long })
        assertEquals(4_600L, selector.delayMs(400))
        assertEquals(now - 6_100L, selector.take(now + 5_000)!!.location.time)
        assertTrue(candidates.isEmpty())
    }

    @Test fun `same capture time retains best complete copied fix`() {
        val selector = LocationFixSelection()
        selector.offer(now, 0, fix("network", 0, 30f))
        val gps = fix("gps", 0, 5f).apply {
            speed = 3f; speedAccuracyMetersPerSecond = .4f
            altitude = 120.0; verticalAccuracyMeters = 2f
        }
        selector.offer(now, 1_000, gps)
        gps.speed = 100f; gps.altitude = 999.0
        val selected = selector.take(now + 5_000)!!.location
        assertEquals("gps", selected.provider)
        assertEquals(3f, selected.speed)
        assertEquals(.4f, selected.speedAccuracyMetersPerSecond)
        assertEquals(120.0, selected.altitude, .0001)
        assertEquals(2f, selected.verticalAccuracyMeters)
    }

}
