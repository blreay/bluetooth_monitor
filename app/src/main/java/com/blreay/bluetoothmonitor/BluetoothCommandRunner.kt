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
    // Terminal/refused means no NEW spawn, not proof that a prior command tree has quiesced.
    private val unresolvedAuthority = AtomicBoolean(false)
    private val spawnLock = Any()

    fun run(arguments: List<String>, timeoutMs: Long = 5_000L): BluetoothCommandResult {
        val process = synchronized(spawnLock) {
            if (terminal.get()) return BluetoothCommandResult(BluetoothCommandResult.Status.NOT_INVOKED,
                if (unresolvedAuthority.get()) "服务已终止；未创建新子进程，旧子进程执行结束未确认" else "服务已终止；未创建子进程",
                !unresolvedAuthority.get())
            val spawned = try { spawn(arguments) } catch (e: Exception) {
                return BluetoothCommandResult(BluetoothCommandResult.Status.NOT_INVOKED, "无法创建 svc 子进程：${e.message}", true)
            }
            unresolvedAuthority.set(true)
            child.set(spawned)
            if (terminal.get()) spawned.destroyForcibly()
            spawned
        }
        val output = StringBuilder()
        val drainCompleted = AtomicBoolean(false)
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
                // Only EOF followed by completed reader cleanup is a successful drain.
                // A dead thread after IOException/interruption is not evidence of descendant quiescence.
                drainCompleted.set(true)
            } catch (_: Exception) { /* Preserve unresolved authority: EOF/cleanup was not confirmed. */ }
        }, "bt-svc-output").apply { isDaemon = true; start() }
        try {
            if (terminal.get()) return stoppedResult()
            val exited = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            var directExited = exited
            if (!exited) {
                terminal.set(true)
                process.destroyForcibly()
                // Direct exit is only part of the fence: inherited output must also finish below.
                directExited = process.waitFor(250, TimeUnit.MILLISECONDS)
            }
            drain.join(250)
            val text = synchronized(output) { output.toString() }
            if (drain.isAlive || !drainCompleted.get()) {
                // Direct wrapper exit/kill cannot exclude descendants when EOF/reader cleanup is unconfirmed.
                // Retain the entire command authority as uncertain, including subsequent terminal refusals.
                unresolvedAuthority.set(true)
                terminal.set(true)
                return BluetoothCommandResult(BluetoothCommandResult.Status.UNCERTAIN,
                    "svc 包装进程已返回/超时，但输出 EOF/清理未确认（可能失败/中断/仍阻塞）；无法排除后继进程继续执行，蓝牙状态未确认。$text", false)
            }
            if (directExited) unresolvedAuthority.set(false)
            if (exited && terminal.get()) return stoppedResult()
            return if (exited) BluetoothCommandResult.fromExit(process.exitValue(), text)
            else BluetoothCommandResult(BluetoothCommandResult.Status.TIMEOUT, "svc 超时；蓝牙状态未确认。$text", !unresolvedAuthority.get())
        } finally {
            child.compareAndSet(process, null)
            if (process.isAlive) process.destroyForcibly()
            // A returned OEM child can leave an uninterruptible pipe reader. Do not accumulate another
            // drain/close pair on subsequent commands. Escaped output keeps descendant authority
            // unresolved, even if the direct wrapper exited; later terminal refusals cannot erase it.
            if (drain.isAlive) {
                terminal.set(true)
                Thread({ try { process.inputStream.close() } catch (_: Exception) {} }, "bt-svc-close")
                    .apply { isDaemon = true; start() }
            }
        }
    }

    fun hasLiveChild(): Boolean = child.get()?.isAlive == true
    fun destroyChild() {
        terminal.set(true)
        // Wait only for spawn/publication, never the command wait or output drain.
        synchronized(spawnLock) { child.getAndSet(null)?.destroyForcibly() }
    }

    private fun stoppedResult() = BluetoothCommandResult(BluetoothCommandResult.Status.UNCERTAIN,
        "服务已终止；子进程曾创建，系统请求可能已提交，状态未确认", !unresolvedAuthority.get())
}
