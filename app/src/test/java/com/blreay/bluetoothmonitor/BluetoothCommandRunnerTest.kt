package com.blreay.bluetoothmonitor

import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class BluetoothCommandRunnerTest {
    private fun command(mode: String): List<String> {
        val classpath = listOf(CommandFixture::class.java, kotlin.Unit::class.java)
            .map { File(requireNotNull(it.protectionDomain).codeSource.location.toURI()).path }
            .joinToString(File.pathSeparator)
        return listOf(File(System.getProperty("java.home"), "bin/java").path,
            "-cp", classpath, CommandFixture::class.java.name, mode)
    }

    @Test fun `destroy during spawn waits for publication and kills the spawned process`() {
        val spawned = CountDownLatch(1)
        val publish = CountDownLatch(1)
        val destroying = CountDownLatch(1)
        val destroyed = CountDownLatch(1)
        val process = WaitingProcess()
        val runner = BluetoothCommandRunner {
            spawned.countDown()
            check(publish.await(2, TimeUnit.SECONDS))
            process
        }
        val workers = Executors.newFixedThreadPool(2)
        try {
            val operation = workers.submit<BluetoothCommandResult> { runner.run(listOf("fixed-test-command")) }
            assertTrue(spawned.await(1, TimeUnit.SECONDS))
            val shutdown = workers.submit {
                destroying.countDown()
                runner.destroyChild()
                destroyed.countDown()
            }
            assertTrue(destroying.await(1, TimeUnit.SECONDS))
            assertFalse("Shutdown returned while the spawned child was still unpublished", destroyed.await(100, TimeUnit.MILLISECONDS))
            publish.countDown()
            shutdown.get(1, TimeUnit.SECONDS)
            assertFalse(process.isAlive)
            assertEquals(BluetoothCommandResult.Status.ERROR, operation.get(1, TimeUnit.SECONDS).status)
        } finally {
            publish.countDown()
            process.destroyForcibly()
            runner.destroyChild()
            workers.shutdownNow()
        }
    }

    @Test fun `terminal shutdown rejects subsequent commands before spawning`() {
        var spawned = false
        val runner = BluetoothCommandRunner { spawned = true; WaitingProcess() }
        runner.destroyChild()
        val result = runner.run(listOf("fixed-test-command"), 50)
        assertFalse(spawned)
        assertEquals(BluetoothCommandResult.Status.ERROR, result.status)
    }

    @Test fun `destroy never waits behind the long command wait`() {
        val process = WaitingProcess()
        val runner = BluetoothCommandRunner { process }
        val worker = Executors.newSingleThreadExecutor()
        try {
            val operation = worker.submit<BluetoothCommandResult> { runner.run(listOf("fixed-test-command")) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while (!runner.hasLiveChild() && System.nanoTime() < deadline) Thread.yield()
            assertTrue(runner.hasLiveChild())
            val before = System.nanoTime()
            runner.destroyChild()
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 500)
            assertEquals(BluetoothCommandResult.Status.ERROR, operation.get(1, TimeUnit.SECONDS).status)
        } finally { process.destroyForcibly(); runner.destroyChild(); worker.shutdownNow() }
    }

    private class WaitingProcess : Process() {
        private val alive = AtomicBoolean(true)
        private val done = CountDownLatch(1)
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun waitFor(): Int { done.await(); return 0 }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = done.await(timeout, unit)
        override fun exitValue(): Int { if (alive.get()) throw IllegalThreadStateException(); return 0 }
        override fun destroy() { alive.set(false); done.countDown() }
        override fun destroyForcibly(): Process { destroy(); return this }
        override fun isAlive(): Boolean = alive.get()
    }

    @Test fun `output is drained beyond the saved cap so a large pipe cannot block completion`() {
        val result = BluetoothCommandRunner().run(command("large"), 5_000)
        assertEquals(BluetoothCommandResult.Status.SUBMITTED, result.status)
        assertEquals(BluetoothCommandResult.OUTPUT_LIMIT, result.output.length)
    }

    @Test fun `stderr and nonzero exit are visible errors`() {
        val result = BluetoothCommandRunner().run(command("refused"), 5_000)
        assertEquals(BluetoothCommandResult.Status.ERROR, result.status)
        assertTrue(result.output.contains("OEM refused"))
        assertTrue(result.output.contains("7"))
    }

    @Test fun `timeout kills the process and returns without an unbounded output join`() {
        val runner = BluetoothCommandRunner()
        val before = System.nanoTime()
        val result = runner.run(command("wait"), 500)
        val elapsedMs = (System.nanoTime() - before) / 1_000_000
        assertEquals(BluetoothCommandResult.Status.TIMEOUT, result.status)
        assertTrue("Took ${elapsedMs}ms", elapsedMs < 3_000)
        assertFalse(runner.hasLiveChild())
    }
}

/** Child JVM avoids shell/test-machine utilities; never part of the APK. */
object CommandFixture {
    @JvmStatic fun main(args: Array<String>) {
        when (args.single()) {
            "large" -> print("x".repeat(200_000))
            "refused" -> { System.err.println("OEM refused"); kotlin.system.exitProcess(7) }
            "wait" -> { println("request started"); Thread.sleep(10_000) }
        }
    }
}
