package com.yuyan.imemodule.data.redpacket

import org.junit.Assert.*
import org.junit.Test

class SilentPacketNativeDiagnosticsTest {
    @Test fun unavailableControlsHaveADistinctTaskOutcome() {
        val diagnostics = SilentPacketDiagnostics { 100 }
        diagnostics.finished("nodes_unavailable")
        assertEquals(SilentPacketDiagnosticEvent.TASK_NODES_UNAVAILABLE, diagnostics.snapshot().lastTask)
    }
    @Test fun unreadableNativeWindowIsVisibleWithoutPretendingOcrSucceeded() {
        val diagnostics = SilentPacketDiagnostics { 100 }
        val facts = NativePacketReadFacts(NativePacketReadStatus.WINDOW_UNAVAILABLE, 8, windowCount = 0)
        diagnostics.native(facts)
        val snapshot = diagnostics.snapshot()
        assertEquals(facts, snapshot.lastNativePage)
        assertTrue(snapshot.render().contains("nativeStatus=WINDOW_UNAVAILABLE"))
        assertNull(snapshot.lastEnginePage)
    }

    @Test fun freshTaskClearsPreviousNodeEvidenceButRetainsEventHistory() {
        val diagnostics = SilentPacketDiagnostics { 100 }
        diagnostics.native(NativePacketReadFacts(NativePacketReadStatus.VALID, 8, nodeCount = 30, cardCount = 1))
        diagnostics.engine(SilentPacketEngineStage.CARD_CLICK, SilentPacketEngineEvent.COMPLETED, 5)
        assertNotNull(diagnostics.snapshot().lastNativePage)
        diagnostics.engine(SilentPacketEngineStage.PREPARE, SilentPacketEngineEvent.STARTED, 0)
        val snapshot = diagnostics.snapshot()
        assertNull(snapshot.lastNativePage)
        assertEquals(2, snapshot.engineHistory.size)
    }

    @Test fun factsNeverRetainTextOrTreesAndMemoryKeepsOnlyLatestObservation() {
        val diagnostics = SilentPacketDiagnostics { 100 }
        repeat(100) { diagnostics.native(NativePacketReadFacts(NativePacketReadStatus.VALID, 8, nodeCount = it)) }
        assertEquals(99, diagnostics.snapshot().lastNativePage?.nodeCount)
        assertTrue(NativePacketReadFacts::class.java.declaredFields.all { it.type.isPrimitive || it.type.isEnum })
        val render = diagnostics.snapshot().render()
        assertTrue(render.contains("nodeCount=99"))
        assertFalse(render.contains("nodeCount=98"))
    }
}
