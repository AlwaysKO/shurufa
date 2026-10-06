package com.yuyan.imemodule.data.collect

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BackgroundRefreshBatchTest {
    @Test fun failedModuleDoesNotPreventOtherChecksInSameBatch() = runBlocking {
        val completed = mutableListOf<String>()
        assertTrue(runBackgroundRefreshSteps({ true }, listOf(
            { completed += "configuration" },
            { throw IllegalStateException("temporarily unavailable") },
            { completed += "dictionary" },
            { completed += "resource_version" },
        )))
        assertEquals(listOf("configuration", "dictionary", "resource_version"), completed)
    }
    @Test fun networkLossOrGamePauseStopsRemainingModulesWithoutFinishingBatch() = runBlocking {
        var allowed = true; var laterCalls = 0
        assertFalse(runBackgroundRefreshSteps({ allowed }, listOf(
            { allowed = false },
            { laterCalls++ },
        )))
        assertEquals(0, laterCalls)
        assertFalse(runBackgroundRefreshSteps({ false }, listOf({ laterCalls++ })))
        assertEquals(0, laterCalls)
    }
    @Test fun cancellationRemainsCancellationInsteadOfSuccessfulRefresh() = runBlocking {
        var laterCalls = 0
        try {
            runBackgroundRefreshSteps({ true }, listOf({ throw CancellationException("stopped") }, { laterCalls++ }))
            fail("cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(0, laterCalls)
    }
}
