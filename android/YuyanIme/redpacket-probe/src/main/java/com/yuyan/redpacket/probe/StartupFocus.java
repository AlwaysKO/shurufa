package com.yuyan.redpacket.probe;

import java.util.function.LongSupplier;

final class StartupFocus {
    interface Source { ProbeSession.Focus get() throws Exception; }
    interface Sleeper { void sleep(long ms) throws InterruptedException; }
    static ProbeSession.Focus await(String main, Source source, LongSupplier clock, Sleeper sleeper)
            throws Exception {
        long deadline = clock.getAsLong() + 1500;
        while (true) {
            ProbeSession.Focus focus = source.get();
            // Only an absent startup window can settle; every other unsafe condition still stops.
            if (!focus.mainSafe() || !main.equals(focus.mainWindow) || !focus.displayOn
                    || focus.secondaryWindow != null) return focus;
            long remaining = deadline - clock.getAsLong();
            if (remaining <= 0) return focus;
            sleeper.sleep(Math.min(100, remaining));
        }
    }
}
