package com.yuyan.redpacket.probe;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
final class HardDeadline {
    private final ScheduledExecutorService timer;
    private final Runnable expired;
    private ScheduledFuture<?> future;
    HardDeadline(ScheduledExecutorService timer, Runnable expired) { this.timer = timer; this.expired = expired; }
    synchronized void arm(long duration) {
        if (future == null) future = timer.schedule(expired, duration, TimeUnit.MILLISECONDS);
    }
    synchronized void disarm() {
        if (future != null) { future.cancel(false); future = null; }
    }
}
