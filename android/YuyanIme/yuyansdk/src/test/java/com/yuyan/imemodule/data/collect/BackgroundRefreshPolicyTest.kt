package com.yuyan.imemodule.data.collect

import org.junit.Assert.*
import org.junit.Test

class BackgroundRefreshPolicyTest {
    @Test fun wifiAndCellularUseOneThirtyMinuteOrTwoHourBatch() {
        assertEquals(1_800_000L, BackgroundRefreshPolicy.intervalMillis(true))
        assertEquals(7_200_000L, BackgroundRefreshPolicy.intervalMillis(false))
        assertFalse(BackgroundRefreshPolicy.due(1_799_999, 0, true, true))
        assertTrue(BackgroundRefreshPolicy.due(1_800_000, 0, true, true))
        assertFalse(BackgroundRefreshPolicy.due(7_199_999, 0, true, false))
        assertTrue(BackgroundRefreshPolicy.due(7_200_000, 0, true, false))
    }
    @Test fun manualRefreshBypassesTimeButNeverOffline() {
        assertTrue(BackgroundRefreshPolicy.due(1, 0, true, false, userInitiated = true))
        assertFalse(BackgroundRefreshPolicy.due(7_200_000, 0, false, true, userInitiated = true))
        assertFalse(BackgroundRefreshPolicy.due(0, null, false, false))
    }
    @Test fun networkRecoveryReevaluatesDueWithoutResettingLastBatch() {
        assertFalse(BackgroundRefreshPolicy.due(2_000_000, 0, true, false))
        assertTrue(BackgroundRefreshPolicy.due(2_000_000, 0, true, true))
        assertFalse(BackgroundRefreshPolicy.due(1000, 0, true, true))
    }
    @Test fun firstBatchAndClockRollbackRemainRecoverable() {
        assertTrue(BackgroundRefreshPolicy.due(0, null, true, true))
        assertTrue(BackgroundRefreshPolicy.due(0, 10_000, true, false))
    }
}
