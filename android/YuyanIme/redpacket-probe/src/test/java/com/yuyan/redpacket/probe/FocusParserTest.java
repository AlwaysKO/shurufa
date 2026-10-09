package com.yuyan.redpacket.probe;

import org.junit.Test;
import static org.junit.Assert.*;

public class FocusParserTest {
    @Test public void extractsPerDisplayWindowWithoutMixingPreloadDisplay() {
        String windows = "Display: mDisplayId=2 (organized)\n mCurrentFocus=null\n"
            + "Display: mDisplayId=7 (organized)\n mCurrentFocus=Window{ab u0 com.android.settings/Settings}\n"
            + "Display: mDisplayId=0 (organized)\n mCurrentFocus=Window{cd u0 com.example.home/Home}\n";
        ProbeSession.Focus f = FocusParser.parse(windows, "FocusedDisplayId: 0\n FocusedDisplayId: 0", 7, true);
        assertEquals(0, f.topDisplay); assertEquals("com.example.home/Home", f.mainWindow);
        assertEquals("com.android.settings/Settings", f.secondaryWindow);
    }
    @Test public void conflictingOrMissingInputFocusIsUnknown() {
        assertEquals(-1, FocusParser.parse("", "FocusedDisplayId: 0\nFocusedDisplayId: 7", 7, true).topDisplay);
        assertEquals(-1, FocusParser.parse("", "", 7, true).topDisplay);
    }
    @Test public void doesNotExposeWindowTitleOrOtherDumpContents() {
        ProbeSession.Focus f = FocusParser.parse("Display: mDisplayId=0\n mCurrentFocus=Window{a u0 com.test/.Main secret-title}", "FocusedDisplayId: 0", 7, true);
        assertNull(f.mainWindow);
    }
}
