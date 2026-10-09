package com.yuyan.redpacket.probe;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.PixelFormat;
import android.hardware.display.VirtualDisplay;
import android.hardware.display.VirtualDisplayConfig;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Display;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

final class ShellDisplay implements ProbeSession.Backend {
    private final Context context;
    private final AtomicLong frames = new AtomicLong();
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread frameThread;
    // Private, non-presentation; remove destroys contained tasks. No power/lock changes.
    static final int FLAGS = (1 << 3) | (1 << 6) | (1 << 8) | (1 << 10)
        | (1 << 11) | (1 << 14) | (1 << 16);
    private String failure = "NONE";
    ShellDisplay(Context base) {
        context = new ContextWrapper(base) {
            @Override public String getPackageName() { return "com.android.shell"; }
            @Override public String getOpPackageName() { return "com.android.shell"; }
        };
    }
    @android.annotation.SuppressLint("WrongConstant") // Shell-only hidden display flags, checked on device.
    @Override public int open() throws Exception {
        frames.set(0); failure = "NONE";
        frameThread = new HandlerThread("probe-frames"); frameThread.start();
        reader = ImageReader.newInstance(480, 800, PixelFormat.RGBA_8888, 2);
        reader.setOnImageAvailableListener(source -> {
            try (Image image = source.acquireLatestImage()) {
                if (image != null) frames.incrementAndGet();
            } catch (IllegalStateException ignored) { /* Reader was released. */ }
        }, new Handler(frameThread.getLooper()));
        VirtualDisplayConfig config = new VirtualDisplayConfig.Builder("YuyanSilentProbe", 480, 800, 160)
            .setSurface(reader.getSurface()).setFlags(FLAGS).build();
        Class<?> globalClass = Class.forName("android.hardware.display.DisplayManagerGlobal");
        Object global = globalClass.getMethod("getInstance").invoke(null);
        display = (VirtualDisplay) globalClass.getMethod("createVirtualDisplay", Context.class,
            MediaProjection.class, VirtualDisplayConfig.class, VirtualDisplay.Callback.class, Executor.class)
            .invoke(global, context, null, config, null, null);
        if (display == null) throw new IllegalStateException("DISPLAY_CREATION_FAILED");
        return display.getDisplay().getDisplayId();
    }
    @Override public void launchSettings(int id) throws Exception {
        if (id <= 0 || display == null || display.getDisplay().getDisplayId() != id)
            throw new IllegalStateException("INVALID_DISPLAY");
        String result = command("am", "start", "-W", "--display", Integer.toString(id),
            "-a", "android.settings.SETTINGS", "-p", "com.android.settings");
        if (!result.contains("Status: ok")) throw new IllegalStateException("SETTINGS_LAUNCH_FAILED");
    }
    @Override public ProbeSession.Focus focus(int id) throws Exception {
        boolean on = id < 0 || (display != null && display.getDisplay().isValid()
            && display.getDisplay().getState() == Display.STATE_ON);
        return FocusParser.parse(command("dumpsys", "window", "displays"), command("dumpsys", "input"), id, on);
    }
    @Override public ProbeSession.Focus awaitFocus(int id, String main) throws Exception {
        return StartupFocus.await(main, () -> focus(id), android.os.SystemClock::elapsedRealtime, Thread::sleep);
    }
    private static String command(String... args) throws Exception {
        Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Thread drain = new Thread(() -> {
            try (java.io.InputStream stream = process.getInputStream()) {
                byte[] buffer = new byte[4096]; int count;
                while ((count = stream.read(buffer)) != -1) {
                    if (bytes.size() + count > 524288) { process.destroyForcibly(); return; }
                    bytes.write(buffer, 0, count);
                }
            } catch (java.io.IOException ignored) { }
        }, "probe-command-output");
        drain.start();
        try {
            if (!process.waitFor(4, TimeUnit.SECONDS)) throw new IllegalStateException("COMMAND_TIMEOUT");
            drain.join(1000);
            if (drain.isAlive() || process.exitValue() != 0) throw new IllegalStateException("COMMAND_FAILED");
            return bytes.toString(StandardCharsets.UTF_8.name());
        } finally { process.destroyForcibly(); }
    }
    @Override public void close() {
        // Attempt every cleanup even when one platform operation fails.
        if (display != null) {
            try { display.release(); } catch (RuntimeException e) { failure = "DISPLAY_RELEASE_FAILED"; }
            display = null;
        }
        if (reader != null) {
            try { reader.setOnImageAvailableListener(null, null); reader.close(); }
            catch (RuntimeException e) { failure = "READER_RELEASE_FAILED"; }
            reader = null;
        }
        if (frameThread != null) { frameThread.quitSafely(); frameThread = null; }
    }
    long frames() { return frames.get(); }
    String failure() { return failure; }
}
