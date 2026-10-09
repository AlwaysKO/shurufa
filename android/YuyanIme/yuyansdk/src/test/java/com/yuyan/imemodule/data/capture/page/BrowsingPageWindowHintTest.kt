package com.yuyan.imemodule.data.capture.page

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BrowsingPageWindowHintTest {
    @Test fun emptyRootGetsOneDelayedRecheckBeforeCandidateIsCreated() = runBlocking {
        var reads = 0
        val waits = mutableListOf<Long>()
        val active = resolveBrowsingWindowHint(current = { true },
            read = { if (++reads == 1) null else "com.ss.android.ugc.aweme" to 7 },
            wait = { waits += it })
        assertEquals("com.ss.android.ugc.aweme" to 7, active)
        assertEquals(2, reads); assertEquals(listOf(800L), waits)
    }
    @Test fun repeatedEmptyRootStopsWithoutUnboundedPolling() = runBlocking {
        var reads = 0; var waits = 0
        assertNull(resolveBrowsingWindowHint(current = { true }, read = { reads++; null }, wait = { waits++ }))
        assertEquals(2, reads); assertEquals(1, waits)
    }
    @Test fun newNavigationOrConsentDuringWaitPreventsSecondRead() = runBlocking {
        var current = true; var reads = 0
        assertNull(resolveBrowsingWindowHint(current = { current }, read = { reads++; null }, wait = { current = false }))
        assertEquals(1, reads)
    }
    @Test fun alreadyConfirmedWindowDoesNotWaitAndLateResultCannotStartCandidate() = runBlocking {
        var reads = 0
        assertEquals("com.tencent.mm" to 7, resolveBrowsingWindowHint(current = { true },
            read = { reads++; "com.tencent.mm" to 7 }, wait = { fail("confirmed window must not wait") }))
        assertEquals(1, reads)
        var current = true
        assertNull(resolveBrowsingWindowHint(current = { current },
            read = { current = false; "com.tencent.mm" to 7 }, wait = { fail("stale result must not retry") }))
    }
}
