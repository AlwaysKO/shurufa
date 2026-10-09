package com.yuyan.redpacket.silent;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
@android.annotation.TargetApi(35)
final class ShellCommand {
    static String run(String... args) throws Exception {
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
}
