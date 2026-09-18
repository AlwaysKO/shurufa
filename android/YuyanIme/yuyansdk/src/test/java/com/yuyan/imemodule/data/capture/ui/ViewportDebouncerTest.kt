package com.yuyan.imemodule.data.capture.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewportDebouncerTest {
    @Test
    fun contextChangesInvalidateQueuedPhysicalCapturesButOrdinaryUpdatesDoNot() {
        val scheduler = FakeScheduler()
        var invalidations = 0
        val debouncer = ViewportDebouncer<String>(scheduler = scheduler, immediateOnContextChange = true,
            onContextInvalidated = { invalidations++ }, onStable = {})
        debouncer.submit(1, "first", "A", contextKey = "app:A")
        val first = invalidations
        debouncer.submit(1, "changed", "A-new-message", contextKey = "app:A")
        assertEquals(first, invalidations)
        debouncer.submit(1, "other", "B", contextKey = "app:B")
        assertEquals(first + 1, invalidations)
        debouncer.close()
        assertEquals(first + 2, invalidations)
    }

    @Test
    fun continuousChangesWithinThreeHundredMillisecondsEmitOnlyLatestSnapshot() {
        val scheduler = FakeScheduler()
        val emitted = mutableListOf<String>()
        val debouncer = ViewportDebouncer<String>(scheduler = scheduler, onStable = { emitted += it })

        debouncer.submit(windowId = 1, signature = "first", value = "first")
        scheduler.advanceBy(100)
        debouncer.submit(windowId = 1, signature = "second", value = "second")
        scheduler.advanceBy(299)
        assertTrue(emitted.isEmpty())

        scheduler.advanceBy(1)
        assertEquals(listOf("second"), emitted)
    }

    @Test
    fun sameViewportSignatureIsNotParsedTwice() {
        val scheduler = FakeScheduler()
        val emitted = mutableListOf<String>()
        val debouncer = ViewportDebouncer<String>(scheduler = scheduler, onStable = { emitted += it })

        debouncer.submit(1, "same", "first")
        scheduler.advanceBy(300)
        debouncer.submit(1, "same", "duplicate")
        scheduler.advanceBy(300)

        assertEquals(listOf("first"), emitted)
    }

    @Test
    fun newSignatureEmitsAfterItRemainsStableForThreeHundredMilliseconds() {
        val scheduler = FakeScheduler()
        val emitted = mutableListOf<String>()
        val debouncer = ViewportDebouncer<String>(scheduler = scheduler, onStable = { emitted += it })

        debouncer.submit(1, "first", "first")
        scheduler.advanceBy(300)
        debouncer.submit(1, "second", "second")
        scheduler.advanceBy(299)
        assertEquals(listOf("first"), emitted)
        scheduler.advanceBy(1)

        assertEquals(listOf("first", "second"), emitted)
    }

    @Test
    fun newWindowInvalidatesPendingWorkFromOldWindow() {
        val scheduler = FakeScheduler()
        val emitted = mutableListOf<String>()
        val debouncer = ViewportDebouncer<String>(scheduler = scheduler, onStable = { emitted += it })

        debouncer.submit(1, "old", "old")
        scheduler.advanceBy(100)
        debouncer.submit(2, "new", "new")
        scheduler.advanceBy(200)
        assertTrue(emitted.isEmpty())
        scheduler.advanceBy(100)

        assertEquals(listOf("new"), emitted)
    }

    @Test
    fun rateLimitKeepsLatestSnapshotAndEmitsTrailingChange() {
        val scheduler = FakeScheduler()
        val emitted = mutableListOf<String>()
        val debouncer = ViewportDebouncer<String>(
            scheduler = scheduler, minIntervalMillis = 2_000,
            clock = { scheduler.now }, onStable = { emitted += it },
        )
        debouncer.submit(1, "first", "first")
        scheduler.advanceBy(300)
        debouncer.submit(1, "second", "second")
        scheduler.advanceBy(400)
        debouncer.submit(1, "third", "third")
        scheduler.advanceBy(1_599)
        assertEquals(listOf("first"), emitted)
        scheduler.advanceBy(1)
        assertEquals(listOf("first", "third"), emitted)
    }

    @Test
    fun closingCancelsRateLimitedTrailingCapture() {
        val scheduler = FakeScheduler()
        val emitted = mutableListOf<String>()
        val debouncer = ViewportDebouncer<String>(
            scheduler = scheduler, minIntervalMillis = 2_000,
            clock = { scheduler.now }, onStable = { emitted += it },
        )
        debouncer.submit(1, "first", "first")
        scheduler.advanceBy(300)
        debouncer.submit(1, "second", "second")
        debouncer.close()
        scheduler.advanceBy(2_000)
        assertEquals(listOf("first"), emitted)
    }

    @Test
    fun firstEntryAndConversationSwitchNeverWaitForCooldown() {
        val scheduler = FakeScheduler()
        val emitted = mutableListOf<String>()
        val debouncer = ViewportDebouncer<String>(
            scheduler = scheduler, immediateOnContextChange = true,
            minIntervalMillis = 2_000, clock = { scheduler.now }, onStable = { emitted += it },
        )
        debouncer.submit(1, "same", "A", contextKey = "app:A")
        assertEquals(listOf("A"), emitted)
        debouncer.submit(1, "same", "B", contextKey = "app:B")
        assertEquals(listOf("A", "B"), emitted)
        debouncer.submit(1, "same", "duplicate", contextKey = "app:B")
        scheduler.advanceBy(2_000)
        assertEquals(listOf("A", "B"), emitted)
    }

    @Test
    fun leavingCancelsPendingAndReentryCapturesImmediately() {
        val scheduler = FakeScheduler()
        val emitted = mutableListOf<String>()
        val debouncer = ViewportDebouncer<String>(
            scheduler = scheduler, immediateOnContextChange = true, onStable = { emitted += it },
        )
        debouncer.submit(1, "first", "first", contextKey = "app:A")
        debouncer.submit(1, "changed", "stale", contextKey = "app:A")
        debouncer.close()
        scheduler.advanceBy(300)
        assertEquals(listOf("first"), emitted)
        debouncer.submit(1, "first", "reentry", contextKey = "app:A")
        assertEquals(listOf("first", "reentry"), emitted)
    }

    @Test
    fun continuousChangesCannotStarveTheLatestCapture() {
        val scheduler = FakeScheduler()
        val emitted = mutableListOf<String>()
        val debouncer = ViewportDebouncer<String>(scheduler = scheduler,
            immediateOnContextChange = true, clock = { scheduler.now }, onStable = { emitted += it })
        debouncer.submit(1, "entry", "entry")
        debouncer.submit(1, "change-0", "change-0")
        repeat(11) { i ->
            scheduler.advanceBy(100)
            debouncer.submit(1, "change-${i + 1}", "change-${i + 1}")
        }
        assertEquals(listOf("entry"), emitted)
        scheduler.advanceBy(100)
        assertEquals(listOf("entry", "change-11"), emitted)
        scheduler.advanceBy(5_000)
        assertEquals(2, emitted.size)
    }

    @Test
    fun snapshotTreeStopsAtDepthAndNodeLimits() {
        val deep = FakeNode("root", listOf(
            FakeNode("one", listOf(FakeNode("two", listOf(FakeNode("three"))))),
        ))
        val depthLimited = snapshotTree(deep, maxDepth = 2, maxNodes = 100)!!
        assertEquals(3, depthLimited.nodeCount())
        assertEquals(3, depthLimited.maxDepth())

        val wide = FakeNode("root", (1..10).map { FakeNode("child-$it") })
        val nodeLimited = snapshotTree(wide, maxDepth = 10, maxNodes = 4)!!
        assertEquals(4, nodeLimited.nodeCount())
    }

    private class FakeScheduler : DebounceScheduler {
        private data class Entry(val dueAt: Long, val task: () -> Unit, var cancelled: Boolean = false)

        var now = 0L
            private set
        private val entries = mutableListOf<Entry>()

        override fun schedule(delayMillis: Long, task: () -> Unit): CancellableTask {
            val entry = Entry(now + delayMillis, task)
            entries += entry
            return CancellableTask { entry.cancelled = true }
        }

        fun advanceBy(milliseconds: Long) {
            now += milliseconds
            val ready = entries.filter { !it.cancelled && it.dueAt <= now }.sortedBy { it.dueAt }
            entries.removeAll(ready.toSet())
            ready.forEach { it.task() }
        }
    }

    private data class FakeNode(
        override val text: String,
        val children: List<FakeNode> = emptyList(),
    ) : SnapshotNodeSource {
        override val viewId: String? = null
        override val className: String? = "Fake"
        override val contentDescription: String? = null
        override val bounds: IntRect = IntRect(0, 0, 10, 10)
        override val childCount: Int get() = children.size
        override fun childAt(index: Int): SnapshotNodeSource = children[index]
    }
}

private fun UiNodeSnapshot.nodeCount(): Int = 1 + children.sumOf { it.nodeCount() }

private fun UiNodeSnapshot.maxDepth(): Int = 1 + (children.maxOfOrNull { it.maxDepth() } ?: 0)
