package com.yuyan.redpacket.probe;

final class ProbeSession {
    static final long DURATION_MS = 300_000;
    interface Backend {
        int open() throws Exception;
        void launchSettings(int display) throws Exception;
        Focus focus(int display) throws Exception;
        default Focus awaitFocus(int display, String main) throws Exception { return focus(display); }
        void close();
    }
    static final class Focus {
        final int topDisplay;
        final String mainWindow, secondaryWindow;
        final boolean displayOn;
        Focus(int top, String main, String secondary, boolean on) {
            topDisplay = top; mainWindow = main; secondaryWindow = secondary; displayOn = on;
        }
        boolean mainSafe() { return topDisplay == 0 && mainWindow != null && !mainWindow.isEmpty(); }
        boolean secondarySafe() {
            return displayOn && secondaryWindow != null && secondaryWindow.startsWith("com.android.settings/");
        }
    }
    static final class Failure {
        final String phase, reason, secondaryKind;
        final int displayId, topDisplay;
        final boolean displayOn;
        Failure(String phase, String reason, int displayId, Focus focus) {
            this.phase = phase; this.reason = reason; this.displayId = displayId;
            topDisplay = focus.topDisplay; displayOn = focus.displayOn;
            secondaryKind = focus.secondaryWindow == null ? "MISSING"
                : focus.secondaryWindow.startsWith("com.android.settings/") ? "SETTINGS" : "OTHER";
        }
    }
    private final Backend backend;
    private boolean owned;
    private int display = -1;
    private long deadline;
    private String state = "IDLE";
    private Failure failure;
    ProbeSession(Backend backend) { this.backend = backend; }
    void start(int uid, long now) {
        if (running()) return;
        if (uid != 2000) { state = "SHELL_REQUIRED"; return; }
        failure = null;
        try {
            Focus before = backend.focus(-1);
            if (!before.mainSafe()) { state = "MAIN_FOCUS_UNKNOWN"; return; }
            owned = true;
            display = backend.open();
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            if (display <= 0) { stop("INVALID_DISPLAY"); return; }
            backend.launchSettings(display);
            Focus after = backend.awaitFocus(display, before.mainWindow);
            if (!after.mainSafe() || !before.mainWindow.equals(after.mainWindow)) {
                stop("MAIN_FOCUS_CHANGED"); return;
            }
            if (!after.secondarySafe()) { secondaryFailure("START", after); return; }
            deadline = now + DURATION_MS;
            state = "RUNNING";
        } catch (Exception e) { stop("START_FAILED"); }
    }
    void check(long now) {
        if (!running()) return;
        if (now >= deadline) { stop("EXPIRED"); return; }
        try {
            Focus f = backend.focus(display);
            if (!f.mainSafe()) stop("MAIN_FOCUS_LOST");
            else if (f.mainWindow.startsWith("com.android.settings/")) stop("MAIN_SETTINGS_IN_USE");
            else if (!f.secondarySafe()) secondaryFailure("MONITOR", f);
        } catch (Exception e) { stop("STATUS_UNKNOWN"); }
    }
    private void secondaryFailure(String phase, Focus focus) {
        String reason = !focus.displayOn ? "DISPLAY_NOT_ON"
            : focus.secondaryWindow == null ? "SECONDARY_WINDOW_MISSING" : "SECONDARY_WINDOW_OTHER";
        failure = new Failure(phase, reason, display, focus);
        stop("SECONDARY_UNVERIFIED");
    }
    void stop(String reason) {
        state = reason;
        display = -1;
        if (owned) {
            owned = false;
            backend.close();
        }
    }
    boolean running() { return "RUNNING".equals(state); }
    int display() { return display; }
    long remaining(long now) { return running() ? Math.max(0, deadline - now) : 0; }
    String state() { return state; }
    Failure failure() { return failure; }
}
