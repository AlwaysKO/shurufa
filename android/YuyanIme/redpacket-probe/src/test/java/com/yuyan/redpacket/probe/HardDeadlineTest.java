package com.yuyan.redpacket.probe;
import java.util.concurrent.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class HardDeadlineTest {
    @Test public void newSessionCanArmAfterPreviousCleanup() throws Exception {
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        CountDownLatch expired = new CountDownLatch(1);
        try {
            HardDeadline deadline = new HardDeadline(timer, expired::countDown);
            deadline.arm(10000); deadline.disarm(); deadline.arm(20);
            assertTrue(expired.await(2, TimeUnit.SECONDS));
        } finally { timer.shutdownNow(); }
    }
    @Test public void blockedWorkerCannotBlockDeadlineAndRepeatDoesNotRenew() throws Exception {
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch blocked = new CountDownLatch(1), release = new CountDownLatch(1), expired = new CountDownLatch(1);
        try {
            worker.submit(() -> { blocked.countDown(); try { release.await(); } catch (InterruptedException ignored) { } });
            assertTrue(blocked.await(2, TimeUnit.SECONDS));
            HardDeadline deadline = new HardDeadline(timer, expired::countDown);
            deadline.arm(30); deadline.arm(10000);
            assertTrue(expired.await(2, TimeUnit.SECONDS));
        } finally { release.countDown(); worker.shutdownNow(); timer.shutdownNow(); }
    }
    @Test public void completedCleanupCanDisarmDeadline() throws Exception {
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        CountDownLatch expired = new CountDownLatch(1);
        try {
            HardDeadline deadline = new HardDeadline(timer, expired::countDown);
            deadline.arm(100); deadline.disarm();
            assertFalse(expired.await(200, TimeUnit.MILLISECONDS));
        } finally { timer.shutdownNow(); }
    }
}
