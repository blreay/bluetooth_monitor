package com.blreay.bandreset.gpt.shizuku;

import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JVM-testable subprocess lifetime / output handling for {@code svc bluetooth}.
 * Runs inside Shizuku's privileged process (UID 0 or 2000), so the command
 * executes without OEM confirmation dialogs.
 */
final class BluetoothCommandRunner {

    private final AtomicReference<Process> child = new AtomicReference<>();
    private final AtomicBoolean terminal = new AtomicBoolean(false);
    private final Object spawnLock = new Object();

    Process spawn(List<String> arguments) throws java.io.IOException {
        return new ProcessBuilder(arguments).redirectErrorStream(true).start();
    }

    BluetoothCommandResult run(List<String> arguments, long timeoutMs) {
        Process process;
        synchronized (spawnLock) {
            if (terminal.get()) return stoppedResult();
            try {
                process = spawn(arguments);
            } catch (Exception e) {
                return new BluetoothCommandResult(
                        BluetoothCommandResult.Status.ERROR,
                        "无法启动 svc：" + e.getMessage());
            }
            child.set(process);
            if (terminal.get()) {
                process.destroyForcibly();
                return stoppedResult();
            }
        }

        StringBuilder output = new StringBuilder();
        Thread drain = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                char[] buffer = new char[512];
                int count;
                while ((count = reader.read(buffer)) >= 0) {
                    synchronized (output) {
                        int keep = Math.min(count, BluetoothCommandResult.OUTPUT_LIMIT - output.length());
                        if (keep > 0) output.append(buffer, 0, keep);
                    }
                }
            } catch (Exception ignored) { /* closing a timed-out process also closes its pipe */ }
        }, "bt-svc-output");
        drain.setDaemon(true);
        drain.start();

        try {
            if (terminal.get()) return stoppedResult();
            boolean exited = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!exited) {
                process.destroyForcibly();
                process.waitFor(250, TimeUnit.MILLISECONDS);
            }
            drain.join(250);
            String text;
            synchronized (output) { text = output.toString(); }
            if (terminal.get()) return stoppedResult();
            return exited
                    ? BluetoothCommandResult.fromExit(process.exitValue(), text)
                    : new BluetoothCommandResult(BluetoothCommandResult.Status.TIMEOUT,
                            "svc 超时；蓝牙状态未确认。" + text);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new BluetoothCommandResult(BluetoothCommandResult.Status.ERROR, "svc 执行被中断");
        } finally {
            child.compareAndSet(process, null);
            if (process.isAlive()) process.destroyForcibly();
            new Thread(() -> {
                try { process.getInputStream().close(); } catch (Exception ignored) {}
            }, "bt-svc-close").start();
        }
    }

    boolean hasLiveChild() { return child.get() != null && child.get().isAlive(); }

    void destroyChild() {
        terminal.set(true);
        synchronized (spawnLock) {
            Process p = child.getAndSet(null);
            if (p != null) p.destroyForcibly();
        }
    }

    private BluetoothCommandResult stoppedResult() {
        return new BluetoothCommandResult(BluetoothCommandResult.Status.ERROR, "服务已终止，未继续执行蓝牙命令");
    }
}