package com.yuyan.redpacket.probe;
import org.junit.Test;
import static org.junit.Assert.*;
public class CallerPolicyTest {
    @Test public void onlyClientCanStartOrReadProbe() {
        assertTrue(CallerPolicy.allowed(10123, 10123, 2000, false));
        assertFalse(CallerPolicy.allowed(2000, 10123, 2000, false));
        assertFalse(CallerPolicy.allowed(10124, 10123, 2000, false));
    }
    @Test public void frameworkShellMayDestroyButOtherAppsMayNot() {
        assertTrue(CallerPolicy.allowed(2000, 10123, 2000, true));
        assertTrue(CallerPolicy.allowed(10123, 10123, 2000, true));
        assertFalse(CallerPolicy.allowed(10124, 10123, 2000, true));
        assertFalse(CallerPolicy.allowed(0, 10123, 2000, true));
    }
}
