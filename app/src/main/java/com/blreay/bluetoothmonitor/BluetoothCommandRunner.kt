package com.blreay.bluetoothmonitor

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** JVM-testable subprocess lifetime/output handling. Only the UID/whitelist policy supplies production arguments. */
internal class BluetoothCommandRunner(
    private val spawn: (List<String>) -> Process = { ProcessBuilder(it).redirectErrorStream(true).start() },
) {
    private val child = AtomicReference<Process?>()
    private val terminal = AtomicBoolean(false)
    private val spawnLock = Any()

    fun run(arguments: List<String>, timeoutMs: Long = 5_000L): BluetoothCommandResult {
        val process = synchronized(spawnLock) {
            if (terminal.get()) return stoppedResult()
            val spawned = spawn(arguments)
            child.set(spawned)
            if (terminal.get()) spawned.destroyForcibly()
            spawned
        }
        val output = StringBuilder()
        // Consume the entire pipe, retaining only bounded text: truncating the reads would deadlock svc.
        val drain = Thread({
            try {
                process.inputStream.reader(Charsets.UTF_8).use { reader ->
                    val buffer = CharArray(512)
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        synchronized(output) {
                            val keep = minOf(count, BluetoothCommandResult.OUTPUT_LIMIT - output.length)
                            if (keep > 0) output.append(buffer, 0, keep)
                        }
                    }
                }
            } catch (_: Exception) { /* Closing a timed-out process also closes its pipe. */ }
        }, "bt-svc-output").apply { isDaemon = true; start() }
        try {
            if (terminal.get()) return stoppedResult()
            val exited = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!exited) {
                process.destroyForcibly()
                process.waitFor(250, TimeUnit.MILLISECONDS)
            }
            drain.join(250)
            val text = synchronized(output) { output.toString() }
            if (terminal.get()) return stoppedResult()
            return if (exited) BluetoothCommandResult.fromExit(process.exitValue(), text)
            else BluetoothCommandResult(BluetoothCommandResult.Status.TIMEOUT, "svc 超时；蓝牙状态未确认。$text")
        } finally {
            child.compareAndSet(process, null)
            if (process.isAlive) process.destroyForcibly()
            // An OEM child might keep a pipe open; closing it must not block the caller either.
            Thread({ try { process.inputStream.close() } catch (_: Exception) {} }, "bt-svc-close")
                .apply { isDaemon = true; start() }
        }
    }

    fun hasLiveChild(): Boolean = child.get()?.isAlive == true
    fun destroyChild() {
        terminal.set(true)
        // Wait only for spawn/publication, never the command wait or output drain.
        synchronized(spawnLock) { child.getAndSet(null)?.destroyForcibly() }
    }

    private fun stoppedResult() = BluetoothCommandResult(BluetoothCommandResult.Status.ERROR, "服务已终止，未继续执行蓝牙命令")
}
