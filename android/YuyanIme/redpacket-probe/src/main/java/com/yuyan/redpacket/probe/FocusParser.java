package com.yuyan.redpacket.probe;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class FocusParser {
    private static final Pattern DISPLAY = Pattern.compile("Display: mDisplayId=(\\d+)");
    private static final Pattern WINDOW = Pattern.compile("mCurrentFocus=Window\\{[0-9a-fA-F]+ u\\d+ ([A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+)\\}");
    private static final Pattern TOP = Pattern.compile("FocusedDisplayId:\\s*(\\d+)");
    static ProbeSession.Focus parse(String windows, String input, int target, boolean on) {
        int current = -1, top = -1;
        boolean conflict = false;
        String main = null, secondary = null;
        for (String line : windows.split("\\n")) {
            Matcher display = DISPLAY.matcher(line);
            if (display.find()) current = Integer.parseInt(display.group(1));
            Matcher window = WINDOW.matcher(line);
            if (window.find()) {
                if (current == 0) main = window.group(1);
                if (target > 0 && current == target) secondary = window.group(1);
            }
        }
        Matcher focused = TOP.matcher(input);
        while (focused.find()) {
            int value = Integer.parseInt(focused.group(1));
            if (top != -1 && top != value) conflict = true;
            top = value;
        }
        return new ProbeSession.Focus(conflict ? -1 : top, main, secondary, on);
    }
}
