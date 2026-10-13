package com.yuyan.redpacket.silent;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
@android.annotation.TargetApi(35)
final class ShellCommand {
    static String run(String... args) throws Exception {return runFor(4000,args);}
    static String runFor(long timeoutMs,String... args) throws Exception {
        if(timeoutMs<=0)throw new IllegalStateException("COMMAND_TIMEOUT");
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(Math.min(timeoutMs,4000));
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
            long remaining=deadline-System.nanoTime();
            if (remaining<=0 || !process.waitFor(remaining,TimeUnit.NANOSECONDS))
                throw new IllegalStateException("COMMAND_TIMEOUT");
            remaining=deadline-System.nanoTime();
            if(drain.isAlive() && remaining>0)TimeUnit.NANOSECONDS.timedJoin(drain,remaining);
            if(drain.isAlive())throw new IllegalStateException("COMMAND_TIMEOUT");
            if(process.exitValue()!=0)throw new IllegalStateException("COMMAND_FAILED");
            return bytes.toString(StandardCharsets.UTF_8.name());
        } finally { process.destroyForcibly(); }
    }
}
