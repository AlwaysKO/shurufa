package com.yuyan.redpacket.probe;
import org.junit.Test;
import static org.junit.Assert.*;
public class ProbeConnectionTest {
    @Test public void returningPageReconnectsAndReadsStatusWithoutStartingExperiment() {
        int[] calls = new int[3];
        ProbeConnection c = new ProbeConnection(() -> calls[0]++, () -> calls[1]++, () -> calls[2]++);
        c.resume(true); assertEquals(1, calls[0]); assertEquals(0, calls[1]);
        c.connected(); assertEquals(1, calls[1]);
        c.disconnected(); c.resume(true); c.connected();
        assertEquals(2, calls[0]); assertEquals(2, calls[1]); assertEquals(0, calls[2]);
    }
    @Test public void refreshWhileDetachedConnectsOnceAndReadsOnCompletion() {
        int[] calls = new int[3];
        ProbeConnection c = new ProbeConnection(() -> calls[0]++, () -> calls[1]++, () -> calls[2]++);
        c.refresh(true); c.refresh(true); assertEquals(1, calls[0]); assertEquals(0, calls[1]);
        c.connected(); c.refresh(true); assertEquals(2, calls[1]);
    }
    @Test public void unavailablePermissionDoesNotConnect() {
        int[] calls = new int[3];
        ProbeConnection c = new ProbeConnection(() -> calls[0]++, () -> calls[1]++, () -> calls[2]++);
        c.resume(false); c.refresh(false);
        assertEquals(0, calls[0]); assertEquals(0, calls[1]); assertEquals(1, calls[2]);
    }
}
