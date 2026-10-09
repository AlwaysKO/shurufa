package com.yuyan.redpacket.probe;

import org.junit.Test;
import static org.junit.Assert.*;

public class ProbeSessionTest {
    static class Backend implements ProbeSession.Backend {
        int id = 7, closed, opened, launched;
        boolean failLaunch;
        ProbeSession.Focus focus = new ProbeSession.Focus(0, "home", "com.android.settings/Settings", true);
        public int open() { opened++; return id; }
        public void launchSettings(int display) { if (failLaunch) throw new IllegalStateException(); launched++; }
        public ProbeSession.Focus focus(int display) { return focus; }
        public void close() { closed++; }
    }
    @Test public void rootCannotCreateDisplay() {
        Backend b = new Backend(); ProbeSession s = new ProbeSession(b);
        s.start(0, 0); assertFalse(s.running()); assertEquals(0, b.opened);
    }
    @Test public void defaultDisplayIsNeverUsedForLaunch() {
        Backend b = new Backend(); b.id = 0; ProbeSession s = new ProbeSession(b);
        s.start(2000, 0); assertFalse(s.running()); assertEquals(0, b.launched); assertEquals(1, b.closed);
    }
    @Test public void missingMainFocusBlocksBeforeOpening() {
        Backend b = new Backend(); b.focus = new ProbeSession.Focus(0, null, null, true);
        ProbeSession s = new ProbeSession(b); s.start(2000, 0);
        assertFalse(s.running()); assertEquals(0, b.opened);
    }
    @Test public void launchFailureReleasesDisplay() {
        Backend b = new Backend(); b.failLaunch = true; ProbeSession s = new ProbeSession(b);
        s.start(2000, 0); assertFalse(s.running()); assertEquals(1, b.closed);
    }
    @Test public void normalMainAppSwitchCanContinueButTopFocusCannotMove() {
        Backend b = new Backend(); ProbeSession s = new ProbeSession(b); s.start(2000, 0);
        b.focus = new ProbeSession.Focus(0, "other-app", "com.android.settings/Settings", true);
        s.check(1000); assertTrue(s.running());
        b.focus = new ProbeSession.Focus(7, "other-app", "com.android.settings/Settings", true);
        s.check(2000); assertFalse(s.running()); assertEquals(1, b.closed);
    }
    @Test public void unknownOrForeignSecondaryWindowStops() {
        for (String secondary : new String[] {null, "com.tencent.mm/LauncherUI", "com.android.settings.evil/Fake"}) {
            Backend b = new Backend(); ProbeSession s = new ProbeSession(b); s.start(2000, 0);
            b.focus = new ProbeSession.Focus(0, "home", secondary, true);
            s.check(1000); assertFalse(s.running()); assertEquals(1, b.closed);
        }
    }
    @Test public void expiryIsOriginalDeadlineAndCloseIsIdempotent() {
        Backend b = new Backend(); ProbeSession s = new ProbeSession(b); s.start(2000, 50);
        s.start(2000, 1000); assertEquals(1, b.opened);
        s.check(300050); assertFalse(s.running()); assertEquals(1, b.closed);
        s.stop("STOPPED"); assertEquals(1, b.closed);
    }
    @Test public void stoppedDisplayReleasesResources() {
        Backend b = new Backend(); ProbeSession s = new ProbeSession(b); s.start(2000, 0);
        b.focus = new ProbeSession.Focus(0, "home", "com.android.settings/Settings", false);
        s.check(1000); assertFalse(s.running()); assertEquals(1, b.closed);
    }
    @Test public void creationMustNotChangeMainWindow() {
        Backend b = new Backend() {
            public void launchSettings(int display) { super.launchSettings(display); focus = new ProbeSession.Focus(0, "settings-on-main", "com.android.settings/Settings", true); }
        };
        ProbeSession s = new ProbeSession(b); s.start(2000, 0);
        assertFalse(s.running()); assertEquals(1, b.closed);
    }
    @Test public void startupMissingWindowIsRecordedBeforeCleanup() {
        Backend b = new Backend();
        b.focus = new ProbeSession.Focus(0, "home", null, true);
        ProbeSession s = new ProbeSession(b); s.start(2000, 0);
        assertEquals("SECONDARY_UNVERIFIED", s.state());
        assertEquals("START", s.failure().phase);
        assertEquals("SECONDARY_WINDOW_MISSING", s.failure().reason);
        assertEquals(7, s.failure().displayId);
        assertEquals(0, s.failure().topDisplay);
        assertTrue(s.failure().displayOn);
        assertEquals("MISSING", s.failure().secondaryKind);
        assertEquals(-1, s.display());
        assertEquals(1, b.closed);
    }
    @Test public void runningDisplayOffIsDistinguishedFromMissingWindow() {
        Backend b = new Backend(); ProbeSession s = new ProbeSession(b); s.start(2000, 0);
        b.focus = new ProbeSession.Focus(0, "home", "com.android.settings/Settings", false);
        s.check(1000);
        assertEquals("MONITOR", s.failure().phase);
        assertEquals("DISPLAY_NOT_ON", s.failure().reason);
        assertFalse(s.failure().displayOn);
        assertEquals("SETTINGS", s.failure().secondaryKind);
    }
    @Test public void foreignWindowReportDoesNotContainWindowNames() {
        Backend b = new Backend(); ProbeSession s = new ProbeSession(b); s.start(2000, 0);
        b.focus = new ProbeSession.Focus(0, "private-main-title", "private.package/PrivateActivity", true);
        s.check(1000);
        assertEquals("SECONDARY_WINDOW_OTHER", s.failure().reason);
        assertEquals("OTHER", s.failure().secondaryKind);
    }
    @Test public void stopRetainsFailureAndNewStartClearsIt() {
        Backend b = new Backend(); ProbeSession s = new ProbeSession(b); s.start(2000, 0);
        b.focus = new ProbeSession.Focus(0, "home", null, true); s.check(1000);
        ProbeSession.Failure failure = s.failure();
        s.stop("STOPPED"); assertSame(failure, s.failure());
        b.focus = new ProbeSession.Focus(0, "home", "com.android.settings/Settings", true);
        s.start(2000, 2000); assertTrue(s.running()); assertNull(s.failure());
    }
    @Test public void openingSettingsOnMainIsReportedAsUserTakeover() {
        Backend b = new Backend(); ProbeSession s = new ProbeSession(b); s.start(2000, 0);
        b.focus = new ProbeSession.Focus(0, "com.android.settings/HWSettings", null, true);
        s.check(1000);
        assertEquals("MAIN_SETTINGS_IN_USE", s.state());
        assertFalse(s.running()); assertNull(s.failure()); assertEquals(1, b.closed);
    }
}
