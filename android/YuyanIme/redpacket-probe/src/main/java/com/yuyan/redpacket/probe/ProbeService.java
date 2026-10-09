package com.yuyan.redpacket.probe;

import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import org.json.JSONObject;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;

public final class ProbeService extends IProbeService.Stub {
    private final int clientUid;
    private final ShellDisplay backend;
    private final ProbeSession session;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
    private final HardDeadline hardDeadline = new HardDeadline(watchdog,
        () -> Process.killProcess(Process.myPid()));
    private ScheduledFuture<?> monitor;
    public ProbeService(Context context) {
        clientUid = context.getApplicationInfo().uid;
        if (clientUid < 10000) throw new SecurityException("INVALID_CLIENT");
        backend = new ShellDisplay(context);
        session = new ProbeSession(backend);
    }
    private void authorize() {
        if (!CallerPolicy.allowed(Binder.getCallingUid(), clientUid, Process.myUid(), false))
            throw new SecurityException("WRONG_CALLER");
    }
    private String run(Callable<String> action) {
        authorize();
        Future<String> task = worker.submit(action);
        try { return task.get(25, TimeUnit.SECONDS); }
        catch (Exception e) {
            task.cancel(true);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return "{\"state\":\"SERVICE_UNRESPONSIVE\"}";
        }
    }
    @Override public String start() {
        authorize();
        // Independent of all display Binder calls; process death releases the display's token.
        hardDeadline.arm(ProbeSession.DURATION_MS);
        return run(() -> {
            // A preceding queued stop/expiry may have disarmed the entry timer.
            hardDeadline.arm(ProbeSession.DURATION_MS);
            session.start(Process.myUid(), SystemClock.elapsedRealtime());
            if (session.running() && monitor == null) {
                monitor = worker.scheduleWithFixedDelay(() -> {
                    session.check(SystemClock.elapsedRealtime());
                    if (!session.running()) finishCleanup();
                }, 2, 2, TimeUnit.SECONDS);
            }
            if (!session.running()) finishCleanup();
            return snapshot();
        });
    }
    @Override public String status() { return run(this::snapshot); }
    @Override public String stop() {
        authorize();
        ScheduledFuture<?> fuse = watchdog.schedule(() -> Process.killProcess(Process.myPid()), 3, TimeUnit.SECONDS);
        return run(() -> {
            session.stop("STOPPED"); finishCleanup(); fuse.cancel(false); return snapshot();
        });
    }
    private void finishCleanup() {
        cancelMonitor();
        // Only our enumerated diagnostic metadata; retained by logcat even if this service exits.
        if (session.failure() != null) {
            try { android.util.Log.i("YuyanProbe", snapshot()); }
            catch (Exception e) { android.util.Log.w("YuyanProbe", "FAILURE_REPORT_UNAVAILABLE"); }
        }
        if (!"NONE".equals(backend.failure())) Process.killProcess(Process.myPid());
        hardDeadline.disarm();
    }
    private void cancelMonitor() {
        if (monitor != null) { monitor.cancel(false); monitor = null; }
    }
    private String snapshot() throws Exception {
        ProbeSession.Failure failure = session.failure();
        Object detail = failure == null ? JSONObject.NULL : new JSONObject()
            .put("phase", failure.phase).put("reason", failure.reason)
            .put("displayId", failure.displayId).put("topDisplay", failure.topDisplay)
            .put("displayOn", failure.displayOn).put("secondaryKind", failure.secondaryKind);
        return new JSONObject().put("state", session.state()).put("uid", Process.myUid())
            .put("displayId", session.display()).put("frames", backend.frames())
            .put("remainingMs", session.remaining(SystemClock.elapsedRealtime()))
            .put("cleanup", backend.failure()).put("failure", detail).toString();
    }
    @Override public void destroy() {
        // The Shizuku server sends this transaction itself when removing/replacing a service.
        if (!CallerPolicy.allowed(Binder.getCallingUid(), clientUid, Process.myUid(), true))
            throw new SecurityException("WRONG_CALLER");
        watchdog.schedule(() -> Process.killProcess(Process.myPid()), 3, TimeUnit.SECONDS);
        worker.execute(() -> {
            session.stop("DESTROYED"); finishCleanup(); worker.shutdown(); System.exit(0);
        });
    }
}
