package com.yuyan.imemodule.data.collect

import org.junit.Assert.*
import org.junit.Test

/** A sensor probe must give GPS its bounded cold-start window despite an inconclusive network fix. */
class LocationMotionProbeEvidenceTest {
    private val start = 1_800_000_000_000L

    @Test fun `fresh network point inside old stay cannot prematurely end sensor cold start`() {
        val policy = BalancedLocationPolicy().apply { updateNetwork(true) }
        fun point(time: Long) = LocationCandidate(23.13, 113.3, 30f, time, "network")
        (0..4).forEach { minute ->
            val time = start + minute * 30_000L
            assertTrue(policy.observe(time, point(time), null, time))
        }
        assertTrue(policy.isStationary)
        val probeStart = start + 150_000L
        assertTrue(policy.beginMotionProbe(probeStart))
        assertEquals(120_000L, policy.confirmationDelayMs(probeStart))

        // New real timestamp and acceptable accuracy, but no evidence of movement
        // or renewed stay after the hardware movement event.
        assertTrue(policy.observe(probeStart + 1_000L, point(probeStart + 1_000L), null, probeStart + 1_000L))
        assertFalse("inconclusive first network fix must leave the GPS confirmation running", policy.isStationary)
        assertEquals(119_000L, policy.confirmationDelayMs(probeStart + 1_000L))
        policy.endConfirmation(probeStart + 120_000L)
        assertTrue("probe remains bounded even with no useful GPS", policy.isStationary)
        assertEquals(480_000L, policy.motionProbeDelayMs(probeStart + 120_000L))
    }
}
