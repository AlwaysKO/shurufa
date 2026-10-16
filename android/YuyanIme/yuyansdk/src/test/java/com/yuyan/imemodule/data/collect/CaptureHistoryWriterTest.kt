package com.yuyan.imemodule.data.collect

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CaptureHistoryWriterTest {
    @Test fun batchingIsBoundedAndNeverWritesOnCallingThread() = runBlocking {
        val dispatcher = newSingleThreadContext("history-test-io")
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val waiting = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val written = CompletableDeferred<List<CaptureHistoryEntry>>()
        try {
            val writer = CaptureHistoryWriter(scope, authorized = { true }, backgroundAllowed = { true },
                pause = { waiting.complete(Unit); release.await() }, write = {
                    assertTrue(Thread.currentThread().name.startsWith("history-test-io"))
                    written.complete(it)
                })
            writer.record(CaptureHistoryEntry(1,CaptureHistoryEvent.LOCATION_RECEIVED),0)
            withTimeout(5000) { waiting.await() }
            repeat(200) { writer.record(CaptureHistoryEntry(it+2L,CaptureHistoryEvent.LOCATION_RECEIVED),0) }
            assertFalse(written.isCompleted)
            release.complete(Unit)
            val batch = withTimeout(5000) { written.await() }
            assertTrue(batch.size <= 129)
            assertEquals(72, batch.single { it.event == CaptureHistoryEvent.HISTORY_DROPPED }.count)
        } finally { scope.cancel(); dispatcher.close() }
    }
    @Test fun revokedEpochIsNotReauthorizedAndFailedWritesDoNotStopConsumer() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var epoch = 1L
        var background = true
        var release = CompletableDeferred<Unit>()
        val batches = mutableListOf<List<CaptureHistoryEntry>>()
        var failNext = false
        try {
            val writer = CaptureHistoryWriter(scope, authorized = { it == epoch }, backgroundAllowed = { background },
                pause = { release.await() }, write = {
                    if (failNext) { failNext = false; error("synthetic disk failure") }
                    batches.add(it)
                })
            writer.record(CaptureHistoryEntry(1,CaptureHistoryEvent.LOCATION_RECEIVED),epoch)
            epoch = 2
            release.complete(Unit)
            assertTrue(batches.isEmpty())
            release = CompletableDeferred()
            writer.record(CaptureHistoryEntry(2,CaptureHistoryEvent.LOCATION_RECEIVED),epoch)
            failNext = true
            release.complete(Unit)
            assertTrue(batches.isEmpty())
            release = CompletableDeferred()
            writer.record(CaptureHistoryEntry(3,CaptureHistoryEvent.LOCATION_RECEIVED),epoch)
            background = false
            release.complete(Unit)
            assertTrue(batches.isEmpty())
            background = true
            release = CompletableDeferred()
            writer.record(CaptureHistoryEntry(4,CaptureHistoryEvent.LOCATION_SAVED),epoch)
            release.complete(Unit)
            assertEquals(CaptureHistoryEvent.LOCATION_SAVED,batches.single().first().event)
            assertEquals(2,batches.single().last().count)
        } finally { scope.cancel() }
    }
}
