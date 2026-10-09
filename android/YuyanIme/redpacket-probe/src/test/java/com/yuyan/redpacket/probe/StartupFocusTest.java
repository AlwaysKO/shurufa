package com.yuyan.redpacket.probe;

import org.junit.Test;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class StartupFocusTest {
    private static ProbeSession.Focus focus(String main, String secondary) {
        return new ProbeSession.Focus(0, main, secondary, true);
    }
    @Test public void missingWindowMayBecomeReadyWhileMainStaysUnchanged() throws Exception {
        AtomicLong clock = new AtomicLong(); AtomicInteger reads = new AtomicInteger();
        ProbeSession.Focus ready = focus("home", "com.android.settings/Settings");
        ProbeSession.Focus result = StartupFocus.await("home",
            () -> reads.incrementAndGet() == 1 ? focus("home", null) : ready,
            clock::get, clock::addAndGet);
        assertSame(ready, result); assertEquals(2, reads.get()); assertEquals(100, clock.get());
    }
    @Test public void absentWindowCannotWaitBeyondDeadline() throws Exception {
        AtomicLong clock = new AtomicLong();
        ProbeSession.Focus missing = focus("home", null);
        assertSame(missing, StartupFocus.await("home", () -> missing, clock::get, clock::addAndGet));
        assertEquals(1500, clock.get());
    }
    @Test public void changedOrUnknownMainAndForeignWindowAreNeverRetried() throws Exception {
        for (ProbeSession.Focus unsafe : new ProbeSession.Focus[] {
            focus("other-main", null), focus(null, null),
            new ProbeSession.Focus(7, "home", null, true),
            focus("home", "other.package/Activity"),
            new ProbeSession.Focus(0, "home", null, false)
        }) {
            AtomicLong clock = new AtomicLong(); AtomicInteger reads = new AtomicInteger();
            assertSame(unsafe, StartupFocus.await("home", () -> { reads.incrementAndGet(); return unsafe; },
                clock::get, clock::addAndGet));
            assertEquals(1, reads.get()); assertEquals(0, clock.get());
        }
    }
    @Test public void samplingTimeConsumesReadinessBudget() throws Exception {
        AtomicLong clock = new AtomicLong();
        ProbeSession.Focus missing = focus("home", null);
        StartupFocus.await("home", () -> { clock.addAndGet(1600); return missing; },
            clock::get, ms -> fail("must not sleep after slow sample"));
        assertEquals(1600, clock.get());
    }
    @Test(expected = InterruptedException.class) public void cancellationIsNotIgnored() throws Exception {
        StartupFocus.await("home", () -> focus("home", null), () -> 0,
            ms -> { throw new InterruptedException(); });
    }
}
